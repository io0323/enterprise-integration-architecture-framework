package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.crypto.PgpDocumentCrypto
import io.eia.b2bgateway.adapters.handoff.S3HandoffFiles
import io.eia.b2bgateway.adapters.outbox.SftpPartnerOutbox
import io.eia.b2bgateway.adapters.partner.LedgerFileRecipients
import io.eia.b2bgateway.adapters.persistence.B2bGatewaySchema
import io.eia.b2bgateway.adapters.persistence.JdbcFileDeliveries
import io.eia.b2bgateway.application.port.inbound.FileDeliverySummary
import io.eia.b2bgateway.application.port.inbound.HandoffMismatch
import io.eia.b2bgateway.application.usecase.DeliverFilesService
import io.eia.b2bgateway.application.usecase.FileDeliveryPorts
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.pgp.PgpVerification
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.platform.testsupport.SftpServerContainer
import io.eia.platform.testsupport.TestKeys
import io.eia.platform.testsupport.TestLedgers
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val PARTNER = "partner01"
private const val BUCKET = "eiaf-file-exchange"
private const val PREFIX = "outbound/sales-daily/"
private const val PARTNER_OUTBOX = "/config/outbox"
private val NOW = Instant.parse("2026-11-02T00:00:00Z")
private const val PASSWORD_BYTES = 16
private const val TRACE_ID_LENGTH = 32
private const val SPAN_ID_LENGTH = 16
private const val PAST_RETENTION_DAYS = 15

private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/**
 * 取引先へのファイルの送信(P12 ⑦a。ADR-0040 §7)の統合テスト。受け渡しの場所(S3 互換ストレージの outbound/)に file-exchange の代わりに
 * ファイルとマニフェストを置き、b2b-gateway の送信の仕組みで取引先の SFTP に届くことを確かめる。
 */
class DeliverFilesIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val seaweed = SeaweedFsContainer(mapOf("eiaf" to listOf("Admin", "Read", "Write", "List")))
        val sshKey = TestKeys.ssh()
        val sftp = SftpServerContainer(PARTNER, sshKey.publicKey)
        val ours = TestKeys.pgp("EIAF <eiaf@example.com>")
        val partner = TestKeys.pgp("Partner <partner@example.com>")
        val ownerPassword = hex(ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes))
        val appPassword = hex(ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes))
        val workDir: Path = Files.createTempDirectory("b2b-files-it")
        lateinit var app: PGSimpleDataSource
        lateinit var store: S3ObjectStore

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun secrets(): EnvSecretProvider {
            val credentials = seaweed.credentials.getValue("eiaf")
            return EnvSecretProvider(
                mapOf(
                    "IT_S3_ACCESS_KEY" to credentials.accessKey,
                    "IT_S3_SECRET_KEY" to credentials.secretKey,
                    "P01_PGP_PUBLIC_KEY" to partner.publicKey,
                    "B2B_PGP_SECRET_KEY" to ours.secretKey,
                    "B2B_PGP_PASSPHRASE" to ours.passphrase,
                    "P01_SSH_KEY" to sshKey.privateKey,
                    "P01_KNOWN_HOSTS" to sftp.knownHostsLine(),
                ),
            )
        }

        /** 送信のユースケース(台帳の接続先はテストのコンテナ。[at] は今の時刻)。 */
        fun service(at: Instant = NOW): DeliverFilesService {
            val ledger =
                (
                    TradingPartnerLedger.parse(
                        TestLedgers.json(TestLedgers.Partner(host = sftp.host, port = sftp.sshPort)),
                    ) as Result.Ok
                ).value
            val secrets = secrets()
            val crypto = PgpDocumentCrypto(ledger, secrets, SecretName("B2B_PGP_SECRET_KEY"), SecretName("B2B_PGP_PASSPHRASE"), workDir)
            val resilience = Resilience("sftp-$PARTNER", ResilienceConfig(attemptTimeout = 30.seconds, retry = null))
            val outbox = SftpPartnerOutbox(ledger, secrets, mapOf(PARTNER to resilience), crypto, workDir)
            return DeliverFilesService(
                FileDeliveryPorts(S3HandoffFiles(store, PREFIX), LedgerFileRecipients(ledger), JdbcFileDeliveries(app, AuditLog()), outbox),
                ::sha256,
                FixedClock(at),
                retention = 14.days,
            )
        }

        suspend fun deliver(at: Instant = NOW): FileDeliverySummary =
            service(at)().shouldBeInstanceOf<Result.Ok<FileDeliverySummary>>().value

        /** file-exchange の代わり: データファイルを写し、マニフェストを最後に置く。[sha] を渡せば、マニフェストの SHA-256 を差し替える。 */
        fun handoff(
            name: String,
            content: ByteArray = "id,amount\n$name,100\n".encodeToByteArray(),
            createdAt: Instant = NOW,
            sha: String = sha256(content),
        ) {
            val dataFile = "$name.csv.gz"
            (store.put(PREFIX + dataFile, content) as Result.Ok).value
            val manifest =
                FileManifest(
                    dataFile,
                    1,
                    sha,
                    "sales_daily.v1",
                    createdAt,
                    "00-${"a".repeat(TRACE_ID_LENGTH)}-${"b".repeat(SPAN_ID_LENGTH)}-01",
                    "corr-$name",
                    content.size.toLong(),
                )
            (store.put(PREFIX + name + FileManifest.SUFFIX, manifest.toJson()) as Result.Ok).value
        }

        /** 取引先の側: 届いたファイルを、自分の鍵で復号して EIAF の署名を確かめる。暗号の中のファイルの名前も返す。 */
        fun open(sealed: ByteArray): ByteArray {
            val keys = (PgpKeys.parse(Secret(ours.publicKey), Secret(partner.secretKey), Secret(partner.passphrase)) as Result.Ok).value
            val source = Files.createTempFile(workDir, "sealed", ".pgp").also { Files.write(it, sealed) }
            val target = Files.createTempFile(workDir, "opened", ".bin")
            (PgpFiles.decryptAndVerify(source, target, keys) as Result.Ok).value.shouldBeInstanceOf<PgpVerification.Verified>()
            return Files.readAllBytes(target)
        }

        fun count(sql: String): Long =
            app.connection.use { c -> c.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0 } }

        beforeSpec {
            postgres.start()
            seaweed.start()
            sftp.start()
            dataSource(postgres.databaseName, postgres.username, postgres.password).connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE b2b_gateway LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE ${B2bGatewaySchema.APP_ROLE} LOGIN PASSWORD '$appPassword'")
                    s.execute("CREATE DATABASE b2b_gateway OWNER b2b_gateway")
                }
            }
            (B2bGatewaySchema.migrate(dataSource("b2b_gateway", "b2b_gateway", ownerPassword)) as Result.Ok).value
            app = dataSource("b2b_gateway", B2bGatewaySchema.APP_ROLE, appPassword)
            store =
                S3ObjectStore(
                    S3StoreConfig(URI(seaweed.endpoint), BUCKET, SecretName("IT_S3_ACCESS_KEY"), SecretName("IT_S3_SECRET_KEY")),
                    secrets(),
                )
            val credentials = seaweed.credentials.getValue("eiaf")
            S3Client
                .builder()
                .endpointOverride(URI(seaweed.endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)),
                ).httpClient(UrlConnectionHttpClient.create())
                .build()
                .use { admin -> admin.createBucket { it.bucket(BUCKET) } }
        }

        afterSpec {
            store.close()
            workDir.toFile().deleteRecursively()
        }

        test("受け渡しの場所のファイルを、取引先あてに暗号化・署名して {データファイル}.pgp で送る。記録と監査(b2b.file.send)を残す") {
            val content = "id,amount\nsales,100\n".encodeToByteArray()
            handoff("sales_daily_20261101150000_001", content)

            deliver().sent shouldContainExactly listOf("sales_daily_20261101150000_001" to PARTNER)

            val remote = sftp.readFile("$PARTNER_OUTBOX/sales_daily_20261101150000_001.csv.gz.pgp").shouldNotBeNull()
            open(remote) shouldBe content
            count("SELECT count(*) FROM b2b.file_delivery WHERE name = 'sales_daily_20261101150000_001' AND NOT already_present") shouldBe 1
            count(
                "SELECT count(*) FROM audit.audit_log WHERE action = 'b2b.file.send' " +
                    "AND target_id = 'sales_daily_20261101150000_001/$PARTNER' " +
                    "AND payload_sha256 = '${sha256(content)}' AND destination = 'partner:$PARTNER'",
            ) shouldBe 1
        }

        test("次の回は、送った組を送り直さない。記録がなく取引先の側にすでにあれば、上書きせずに記録する") {
            deliver().sent.shouldBeEmpty()

            handoff("sales_daily_20261101160000_001")
            sftp.writeFile("$PARTNER_OUTBOX/sales_daily_20261101160000_001.csv.gz.pgp", "sent before the crash".encodeToByteArray())
            deliver().sent shouldContainExactly listOf("sales_daily_20261101160000_001" to PARTNER)

            sftp.readFile("$PARTNER_OUTBOX/sales_daily_20261101160000_001.csv.gz.pgp")?.decodeToString() shouldBe "sent before the crash"
            count("SELECT count(*) FROM b2b.file_delivery WHERE name = 'sales_daily_20261101160000_001' AND already_present") shouldBe 1
        }

        test("過去の日付で作り直したファイルを後から置いても送る(名前の順で前に来ても、毎回すべて一覧する。承認の条件 1)") {
            handoff("sales_daily_20261015150000_002")

            deliver().sent shouldContainExactly listOf("sales_daily_20261015150000_002" to PARTNER)

            sftp.list(PARTNER_OUTBOX).filter { it.startsWith("sales_daily_20261015150000_002") } shouldContainExactly
                listOf("sales_daily_20261015150000_002.csv.gz.pgp")
        }

        test("データファイルの SHA-256 がマニフェストと違えば送らない。受け取る取引先のないデータセットは送らない") {
            handoff("sales_daily_20261101170000_001", sha = sha256("not the content".encodeToByteArray()))
            handoff("sales_weekly_20261101170000_001")

            val summary = deliver()

            summary.sent.shouldBeEmpty()
            summary.failed.map { it.error } shouldContainExactly listOf(HandoffMismatch("sales_daily_20261101170000_001"))
            sftp
                .list(
                    PARTNER_OUTBOX,
                ).filter { it.startsWith("sales_daily_20261101170000_001") || it.startsWith("sales_weekly") }
                .shouldBeEmpty()
        }

        test("保存期間(14 日)を過ぎた、送り終えた・受け取る取引先のないファイルは受け渡しの場所から消す。送れていないファイルは消さない") {
            val summary = deliver(at = NOW + PAST_RETENTION_DAYS.days)

            summary.deleted shouldContainExactly
                listOf(
                    "sales_daily_20261015150000_002",
                    "sales_daily_20261101150000_001",
                    "sales_daily_20261101160000_001",
                    "sales_weekly_20261101170000_001",
                )
            store
                .list(PREFIX)
                .let { it as Result.Ok }
                .value
                .map { it.key.removePrefix(PREFIX) } shouldContainExactly
                listOf("sales_daily_20261101170000_001.csv.gz", "sales_daily_20261101170000_001.manifest.json")
        }
    })
