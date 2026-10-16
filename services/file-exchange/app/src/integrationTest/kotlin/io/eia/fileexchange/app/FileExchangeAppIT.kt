@file:Suppress("MagicNumber", "NestedBlockDepth") // テストデータの件数。DB を読む補助の関数の入れ子

package io.eia.fileexchange.app

import io.eia.fileexchange.adapters.file.SalesDailyLayout
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.filetransfer.write.PublishRequest
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.security.SecureRandom
import java.time.LocalDate
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * file-exchange のコマンド(migrate・receive・sweep)と常駐(serve)を、実際の PostgreSQL と S3 互換ストレージ(SeaweedFS)で確かめる。
 * serve は受け口のマニフェストの到着で受け取りを起動し、実行の span の親はマニフェストの traceparent(送り手とトレースがつながる)。
 */
class FileExchangeAppIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val bucket = "eiaf-file-exchange"
        val inbox = "inbox/sales-daily/"
        val seaweed =
            SeaweedFsContainer(
                mapOf(
                    "eiaf" to listOf("Admin", "Read", "Write", "List"),
                    "eiaf-file-batch-etl" to listOf("Write:$bucket/$inbox*"),
                    "eiaf-file-exchange" to listOf("Read:$bucket", "List:$bucket", "Write:$bucket"),
                ),
            )
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val workDir = Files.createTempDirectory("file-exchange-app-it")
        val ktor = HttpClient(CIO)
        val sequence = AtomicInteger()
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        fun ownerEnv(): Map<String, String> =
            mapOf(
                FileExchangeConfig.DB_URL to postgres.jdbcUrl.replaceAfterLast('/', "file_exchange"),
                FileExchangeConfig.OWNER_PASSWORD.value to ownerPassword,
            )

        fun appEnv(): Map<String, String> {
            val credentials = seaweed.credentials.getValue("eiaf-file-exchange")
            return mapOf(
                FileExchangeConfig.DB_URL to postgres.jdbcUrl.replaceAfterLast('/', "file_exchange"),
                FileExchangeConfig.APP_PASSWORD.value to appPassword,
                FileExchangeConfig.S3_ENDPOINT to seaweed.endpoint,
                FileExchangeConfig.S3_ACCESS_KEY.value to credentials.accessKey,
                FileExchangeConfig.S3_SECRET_KEY.value to credentials.secretKey,
                FileExchangeConfig.HEALTH_PORT to "0",
                FileExchangeConfig.POLL_INTERVAL to "1s",
                FileExchangeConfig.SWEEP_INTERVAL to "1s",
                "EIA_LOG_FORMAT" to "console",
            )
        }

        fun store(identity: String): S3ObjectStore {
            val credentials = seaweed.credentials.getValue(identity)
            return S3ObjectStore(
                S3StoreConfig(URI(seaweed.endpoint), bucket, SecretName("A"), SecretName("S")),
                EnvSecretProvider(mapOf("A" to credentials.accessKey, "S" to credentials.secretKey)),
            )
        }

        fun keys(prefix: String): List<String> =
            store("eiaf").use { (it.list(prefix) as? Result.Ok)?.value?.map { o -> o.key } ?: fail("一覧できません") }

        fun query(sql: String): String? =
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl.replaceAfterLast('/', "file_exchange"))
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
                }

        /** 送り手として売上ファイルを置き、名前を返す。 */
        fun publish(seq: Int = sequence.incrementAndGet()): String {
            val name = (FileName.of(SalesDailyLayout.V1, Instant.parse("2026-10-14T15:00:00Z"), seq, FileFormat.PARQUET) as Result.Ok).value
            val rows =
                (1..3).asSequence().map {
                    listOf(
                        LocalDate.of(2026, 10, 14),
                        "o-$it",
                        java.time.Instant.parse("2026-10-14T01:00:00Z"),
                        "PLACED",
                        100L,
                        "JPY",
                        "JP",
                        null,
                    )
                }
            store("eiaf-file-batch-etl").use { writer ->
                val request =
                    PublishRequest(
                        inbox,
                        SalesDailyLayout.V1,
                        name,
                        "00-$traceId-00f067aa0ba902b7-01",
                        "corr-app-it",
                        Instant.parse("2026-10-14T16:00:00Z"),
                    )
                (FilePublisher(writer, workDir).publish(request, rows) as? Result.Ok) ?: fail("置けません")
            }
            return name.base
        }

        beforeSpec {
            postgres.start()
            seaweed.start()
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE ROLE file_exchange LOGIN PASSWORD '$ownerPassword'")
                        s.execute("CREATE ROLE file_exchange_app LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                        s.execute("CREATE DATABASE file_exchange OWNER file_exchange")
                        s.execute("REVOKE ALL ON DATABASE file_exchange FROM PUBLIC")
                        s.execute("GRANT CONNECT ON DATABASE file_exchange TO file_exchange_app")
                    }
                }
            val admin = seaweed.credentials.getValue("eiaf")
            S3Client
                .builder()
                .endpointOverride(URI(seaweed.endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(admin.accessKey, admin.secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build()
                .use { it.createBucket { request -> request.bucket(bucket) } }
        }

        afterSpec {
            ktor.close()
            seaweed.stop()
            postgres.stop()
            workDir.toFile().deleteRecursively()
        }

        test("migrate は所有者で、serve・receive・sweep はアプリのロールだけで動く。使い方・設定の誤りは 2") {
            FileExchangeCommands.run(listOf("migrate"), ownerEnv()) shouldBe FileExchangeCommands.OK
            FileExchangeCommands.run(listOf("migrate"), mapOf(FileExchangeConfig.DB_URL to "jdbc:postgresql://x/y")) shouldBe
                FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("serve"), appEnv() + (FileExchangeConfig.OWNER_PASSWORD.value to ownerPassword)) shouldBe
                FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("receive"), appEnv()) shouldBe FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("receive", "a/b"), appEnv()) shouldBe FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("receive", "x", "--force"), appEnv()) shouldBe FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("sweep", "x"), appEnv()) shouldBe FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("sweep"), appEnv() - FileExchangeConfig.S3_ENDPOINT) shouldBe FileExchangeCommands.USAGE
            FileExchangeCommands.run(listOf("unknown"), appEnv()) shouldBe FileExchangeCommands.USAGE
        }

        test("receive: 1 つのファイルを受け取る。sweep: 受け取り済みの名前で届いたファイルを送り直しとして移す") {
            val name = publish()
            FileExchangeCommands.run(listOf("receive", name), appEnv()) shouldBe FileExchangeCommands.OK
            keys("archive/sales-daily/").filter { it.contains(name) }.size shouldBe 2
            query("SELECT state FROM exchange.received_file WHERE file_name = '$name'") shouldBe "STORED"

            publish(seq = name.substringAfterLast('_').toInt())
            FileExchangeCommands.run(listOf("sweep"), appEnv()) shouldBe FileExchangeCommands.OK
            query("SELECT reason FROM exchange.extra_arrival WHERE file_name = '$name'") shouldBe "DUPLICATE"
            keys(inbox).none { it.contains(name) } shouldBe true
        }

        test("serve: マニフェストの到着で受け取り、ready になる。実行の span はマニフェストの traceparent と同じトレース") {
            val name = publish()
            val missing = publish().also { m -> store("eiaf").use { (it.delete("$inbox$m.parquet") as? Result.Ok) ?: fail("消せません") } }
            val server = (FileExchangeServer.start(appEnv()) as? Result.Ok)?.value ?: fail("起動できません")
            try {
                withTimeout(60.seconds) {
                    while (
                        query(
                            "SELECT count(*) FROM batch.batch_run WHERE status = 'SUCCEEDED' AND partition_key IN ('$name', '$missing')",
                        ) !=
                        "2"
                    ) {
                        delay(500)
                    }
                }
                ktor.get("http://localhost:${server.healthPort}/health/ready").status shouldBe HttpStatusCode.OK
                ktor.get("http://localhost:${server.healthPort}/health/live").status shouldBe HttpStatusCode.OK
                keys("archive/sales-daily/").filter { it.contains(name) } shouldContainExactly
                    listOf("archive/sales-daily/$name.manifest.json", "archive/sales-daily/$name.parquet")
                // データファイルがなくても起動し、欠損として quarantine に送る
                query("SELECT reason FROM exchange.received_file WHERE file_name = '$missing'") shouldBe "data_missing"
                query("SELECT trigger_kind FROM batch.batch_run WHERE partition_key = '$name'") shouldBe "FILE"
                query("SELECT substring(run_traceparent from 4 for 32) FROM batch.batch_run WHERE partition_key = '$name'") shouldBe traceId
                query("SELECT correlation_id FROM batch.batch_run WHERE partition_key = '$name'") shouldBe "corr-app-it"
            } finally {
                server.stop()
            }
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
