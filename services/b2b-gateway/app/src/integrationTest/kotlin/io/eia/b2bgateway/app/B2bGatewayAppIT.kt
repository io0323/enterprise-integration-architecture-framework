package io.eia.b2bgateway.app

import io.eia.b2bgateway.adapters.persistence.B2bGatewaySchema
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.pgp.PgpVerification
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.filetransfer.sftp.SftpSender
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.platform.testsupport.SftpServerContainer
import io.eia.platform.testsupport.TestKeys
import io.eia.platform.testsupport.TestLedgers
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val PARTNER = "partner01"
private const val BUCKET = "eiaf-b2b-originals"
private const val PASSWORD_BYTES = 16
private const val POLL_MILLIS = 500L
private const val HANDOFF_BUCKET = "eiaf-file-exchange"
private const val HANDOFF = "outbound/sales-daily/"
private const val SALES_FILE = "sales_daily_20261101150000_001"
private const val TRACE_ID_LENGTH = 32
private const val SPAN_ID_LENGTH = 16

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes))

/** 契約のサンプルの注文(edifact-orders.v1。明細 2 つ)の交換。 */
private fun orders(reference: String): ByteArray {
    val body =
        listOf(
            "BGM+220+PO-$reference+9",
            "DTM+137:20261030:102",
            "NAD+BY+4912345000019::9",
            "NAD+SU+4900000000001::9",
            "CUX+2:JPY:9",
            "LIN+1++4901234567894:EN",
            "PIA+1+ABC-123:BP",
            "QTY+21:10",
            "PRI+AAA:1200",
            "LIN+2++4901234567900:EN",
            "QTY+21:5",
            "PRI+AAA:980",
            "UNS+S",
            "CNT+2:2",
        )
    val segments =
        listOf("UNB+UNOA:3+4912345000019:14+4900000000001:14+261030:1200+$reference", "UNH+1+ORDERS:D:96A:UN") + body +
            listOf("UNT+${body.size + 2}+1", "UNZ+1+$reference")
    return ("UNA:+.? '" + segments.joinToString("'", postfix = "'")).encodeToByteArray()
}

/**
 * b2b-gateway の app の統合テスト(P12 ⑥a)。PostgreSQL・S3 互換ストレージ・取引先の SFTP のコンテナと、order の API と Keycloak の代わり
 * (Ktor のサーバ)で、コマンド(migrate・codes import)と常駐(serve)の全体を確かめる。
 */
class B2bGatewayAppIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val seaweed = SeaweedFsContainer(mapOf("eiaf" to listOf("Admin", "Read", "Write", "List")))
        val sshKey = TestKeys.ssh()
        val sftp = SftpServerContainer(PARTNER, sshKey.publicKey)
        val ours = TestKeys.pgp("EIAF <eiaf@example.com>")
        val partner = TestKeys.pgp("Partner <partner@example.com>")
        val archiveKey = TestKeys.pgp("EIAF archive <archive@example.com>")
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val workDir: Path = Files.createTempDirectory("b2b-app-it")
        val ledgerFile = workDir.resolve("partners.json")
        val ktor = HttpClient(CIO)
        // order の API の代わりが受け取った要求(Idempotency-Key → 本文)
        val orders = ConcurrentHashMap<String, String>()
        val stub =
            embeddedServer(Netty, configure = { connector { port = 0 } }) {
                routing {
                    post("/token") {
                        call.respondText(
                            """{"access_token":"t-1","token_type":"Bearer","expires_in":300,"scope":"sales.order:write"}""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/sales/v1/orders") {
                        val key = call.request.header("Idempotency-Key") ?: ""
                        orders[key] = call.receiveText()
                        call.respondText(
                            """{"id":"ord-${orders.size}","status":"PLACED"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                }
            }
        lateinit var admin: S3Client

        fun dbUrl() = postgres.jdbcUrl.replaceAfterLast('/', "b2b_gateway")

        fun ownerEnv() = mapOf(B2bGatewayConfig.DB_URL to dbUrl(), B2bGatewayConfig.OWNER_PASSWORD.value to ownerPassword)

        fun appEnv(): Map<String, String> {
            val credentials = seaweed.credentials.getValue("eiaf")
            val stubUrl = "http://localhost:${runBlocking {
                stub.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }}"
            return mapOf(
                B2bGatewayConfig.DB_URL to dbUrl(),
                B2bGatewayConfig.APP_PASSWORD.value to appPassword,
                B2bGatewayConfig.PARTNERS_FILE to ledgerFile.toString(),
                B2bGatewayConfig.S3_ENDPOINT to seaweed.endpoint,
                B2bGatewayConfig.S3_ACCESS_KEY.value to credentials.accessKey,
                B2bGatewayConfig.S3_SECRET_KEY.value to credentials.secretKey,
                B2bGatewayConfig.ORIGINAL_RETENTION to "3m",
                B2bGatewayConfig.PGP_SECRET_KEY.value to ours.secretKey,
                B2bGatewayConfig.PGP_PASSPHRASE.value to ours.passphrase,
                B2bGatewayConfig.ARCHIVE_PGP_PUBLIC_KEY.value to archiveKey.publicKey,
                B2bGatewayConfig.GATEWAY_URL to stubUrl,
                B2bGatewayConfig.TOKEN_URL to "$stubUrl/token",
                B2bGatewayConfig.CLIENT_SECRET.value to "client-secret",
                B2bGatewayConfig.HEALTH_PORT to "0",
                B2bGatewayConfig.POLL_INTERVAL to "1s",
                B2bGatewayConfig.SEND_INTERVAL to "1s",
                B2bGatewayConfig.SUBMIT_INTERVAL to "1s",
                B2bGatewayConfig.METRICS_INTERVAL to "1s",
                B2bGatewayConfig.WORK_DIR to workDir.toString(),
                B2bGatewayConfig.AUDIT_S3_ENDPOINT to seaweed.endpoint,
                B2bGatewayConfig.AUDIT_S3_ACCESS_KEY.value to credentials.accessKey,
                B2bGatewayConfig.AUDIT_S3_SECRET_KEY.value to credentials.secretKey,
                B2bGatewayConfig.ANCHOR_RETENTION to "3m",
                B2bGatewayConfig.ANCHOR_INTERVAL to "1s",
                B2bGatewayConfig.FILE_S3_ENDPOINT to seaweed.endpoint,
                B2bGatewayConfig.FILE_S3_ACCESS_KEY.value to credentials.accessKey,
                B2bGatewayConfig.FILE_S3_SECRET_KEY.value to credentials.secretKey,
                B2bGatewayConfig.DELIVER_INTERVAL to "1s",
                "P01_PGP_PUBLIC_KEY" to partner.publicKey,
                "P01_SSH_KEY" to sshKey.privateKey,
                "P01_KNOWN_HOSTS" to sftp.knownHostsLine(),
                "EIA_LOG_FORMAT" to "console",
            )
        }

        fun query(sql: String): String {
            val dataSource =
                PGSimpleDataSource().apply {
                    setURL(dbUrl())
                    user = B2bGatewaySchema.APP_ROLE
                    password = appPassword
                }
            return dataSource.connection.use { c ->
                c.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else "" }
            }
        }

        /** 取引先の側: 署名して自社あてに暗号化し、受け取りのディレクトリに置く(一時名で書いてから正式名に変える)。 */
        fun place(
            name: String,
            plain: ByteArray,
        ) {
            val keys = (PgpKeys.parse(Secret(ours.publicKey), Secret(partner.secretKey), Secret(partner.passphrase)) as Result.Ok).value
            val source = Files.createTempFile(workDir, "plain", ".edi").also { Files.write(it, plain) }
            val sealed = Files.createTempFile(workDir, "sealed", ".pgp")
            PgpFiles.encryptAndSign(source, sealed, keys, "orders.edi")
            val secrets = EnvSecretProvider(appEnv())
            val sender =
                SftpSender(SftpConfig(sftp.host, sftp.sshPort, PARTNER, SecretName("P01_SSH_KEY"), SecretName("P01_KNOWN_HOSTS")), secrets)
            (sender.send(sealed, "/config/to-eiaf", name) as Result.Ok).value
        }

        /** 取引先の側: 自社の署名を確かめて、応答を復号する。 */
        fun open(sealed: ByteArray): String {
            val keys = (PgpKeys.parse(Secret(ours.publicKey), Secret(partner.secretKey), Secret(partner.passphrase)) as Result.Ok).value
            val source = Files.createTempFile(workDir, "ack", ".pgp").also { Files.write(it, sealed) }
            val target = Files.createTempFile(workDir, "ack", ".edi")
            (PgpFiles.decryptAndVerify(source, target, keys) as Result.Ok).value.shouldBeInstanceOf<PgpVerification.Verified>()
            return Files.readString(target, Charsets.ISO_8859_1)
        }

        fun csv(
            name: String,
            text: String,
        ): String = workDir.resolve(name).also { Files.writeString(it, text.trimIndent() + "\n") }.toString()

        beforeSpec {
            postgres.start()
            seaweed.start()
            sftp.start()
            stub.start(wait = false)
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE ROLE b2b_gateway LOGIN PASSWORD '$ownerPassword'")
                        s.execute(
                            "CREATE ROLE ${B2bGatewaySchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                        )
                        s.execute("CREATE DATABASE b2b_gateway OWNER b2b_gateway")
                    }
                }
            Files.writeString(ledgerFile, TestLedgers.json(TestLedgers.Partner(host = sftp.host, port = sftp.sshPort)))
            val credentials = seaweed.credentials.getValue("eiaf")
            admin =
                S3Client
                    .builder()
                    .endpointOverride(URI(seaweed.endpoint))
                    .region(Region.US_EAST_1)
                    .forcePathStyle(true)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)),
                    ).httpClient(UrlConnectionHttpClient.create())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .build()
            admin.createBucket { it.bucket(BUCKET).objectLockEnabledForBucket(true) }
            admin.createBucket { it.bucket("eiaf-audit").objectLockEnabledForBucket(true) }
            admin.createBucket { it.bucket("eiaf-file-exchange") }
        }

        afterSpec {
            stub.stop(0, 0)
            ktor.close()
            admin.close()
            workDir.toFile().deleteRecursively()
        }

        test("migrate: 所有者の資格情報でマイグレーションする。serve・codes の環境に所有者のパスワードは渡せない") {
            B2bGatewayCommands.run(listOf("migrate"), ownerEnv()) shouldBe B2bGatewayCommands.OK
            B2bGatewayCommands.run(listOf("serve"), appEnv() + ownerEnv()) shouldBe B2bGatewayCommands.USAGE
        }

        test("codes import: CSV で取引先の対応表を入れ替える。誤りのあるファイルでは何も変えずに 2 で終わる") {
            fun import(
                kind: String,
                file: String,
            ) = B2bGatewayCommands.run(listOf("codes", "import", PARTNER, kind, file, "--operator", "ops-1"), appEnv())

            import(
                "buyers",
                csv(
                    "buyers.csv",
                    """
                    gln,customer_id,country_code,postal_code,region,city,line1,line2
                    4912345000019,cust-1,JP,100-0001,Tokyo,Chiyoda,1-1,
                    """,
                ),
            ) shouldBe B2bGatewayCommands.OK
            import(
                "items",
                csv(
                    "items.csv",
                    """
                    code_type,code,product_id,sku
                    buyer_part_number,ABC-123,prod-1,SKU-1
                    gtin,4901234567900,prod-2,SKU-2
                    """,
                ),
            ) shouldBe B2bGatewayCommands.OK
            import("items", csv("bad.csv", "code_type,code,product_id,sku\nean,1,p,s")) shouldBe B2bGatewayCommands.USAGE

            query("SELECT version || ':' || rows FROM b2b.code_table WHERE partner_id = '$PARTNER' AND kind = 'items'") shouldBe "1:2"
            query("SELECT count(*) FROM audit.audit_log WHERE action = 'b2b.codes.import' AND actor_id = 'ops-1'") shouldBe "2"
        }

        test("serve: 取引先が置いた注文を受け付けて CONTRL を返し、order の API に取り込んで肯定の APERAK を返す。受け渡しの場所のファイルを送る。ready になる") {
            val server = (B2bGatewayServer.start(appEnv()) as? Result.Ok)?.value ?: fail("起動できません")
            try {
                place("orders-1.edi.pgp", orders("APP1001"))
                // file-exchange の代わりに、受け渡しの場所に売上ファイルとマニフェスト(最後)を置く(P12 ⑦。ADR-0040 §7)
                val sales = "id,amount\napp-it,100\n".encodeToByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(sales).joinToString("") { "%02x".format(it) }
                val traceparent = "00-${"a".repeat(TRACE_ID_LENGTH)}-${"b".repeat(SPAN_ID_LENGTH)}-01"
                val manifest =
                    """{"file":"$SALES_FILE.csv.gz","recordCount":1,"sha256":"$digest","schemaVersion":"sales_daily.v1",""" +
                        """"createdAt":"2026-11-01T15:00:00Z","traceparent":"$traceparent","correlationId":"app-it"}"""
                admin.putObject({ it.bucket(HANDOFF_BUCKET).key("$HANDOFF$SALES_FILE.csv.gz") }, RequestBody.fromBytes(sales))
                admin.putObject({ it.bucket(HANDOFF_BUCKET).key("$HANDOFF$SALES_FILE.manifest.json") }, RequestBody.fromString(manifest))

                withTimeout(90.seconds) {
                    while (query("SELECT count(*) FROM b2b.acknowledgement WHERE state = 'SENT'") != "2") delay(POLL_MILLIS)
                }

                orders.keys shouldBe setOf("b2b:$PARTNER:APP1001:1")
                orders.getValue("b2b:$PARTNER:APP1001:1") shouldContain """"customerId":"cust-1""""
                query("SELECT state || ':' || order_id FROM b2b.order_submission") shouldBe "ACCEPTED:ord-1"
                val sent = sftp.list("/config/from-eiaf")
                val contrl = sent.single { it.startsWith("contrl-") }
                val aperak = sent.single { it.startsWith("aperak-") }
                open(sftp.readFile("/config/from-eiaf/$contrl") ?: fail("CONTRL がありません")) shouldContain "UCI+APP1001+"
                open(sftp.readFile("/config/from-eiaf/$aperak") ?: fail("APERAK がありません")) shouldContain "+9+AP'"
                sftp.list("/config/to-eiaf/processed").single() shouldContain "orders-1.edi.pgp"
                withTimeout(30.seconds) {
                    while (sftp.readFile("/config/outbox/$SALES_FILE.csv.gz.pgp") == null) delay(POLL_MILLIS)
                }
                query("SELECT partner_id FROM b2b.file_delivery WHERE name = '$SALES_FILE'") shouldBe PARTNER
                // 監査のアンカー(ハッシュチェーンの末尾)を、b2b-gateway の接頭辞に保存する(ADR-0017 §5)
                withTimeout(30.seconds) {
                    while (admin.listObjectsV2 { it.bucket("eiaf-audit").prefix("anchors/b2b-gateway/") }.keyCount() ==
                        0
                    ) {
                        delay(POLL_MILLIS)
                    }
                }
                ktor.get("http://localhost:${server.healthPort}/health/ready").status shouldBe HttpStatusCode.OK
                ktor.get("http://localhost:${server.healthPort}/health/live").status shouldBe HttpStatusCode.OK
            } finally {
                server.stop()
            }
        }
    })
