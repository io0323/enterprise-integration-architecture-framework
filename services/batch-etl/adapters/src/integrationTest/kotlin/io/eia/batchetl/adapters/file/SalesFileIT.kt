@file:Suppress("MagicNumber") // テストデータの件数・版・パスワードの長さ

package io.eia.batchetl.adapters.file

import io.eia.batchetl.adapters.persistence.BatchEtlSchema
import io.eia.batchetl.adapters.persistence.JdbcDailySalesLines
import io.eia.batchetl.application.port.outbound.SalesFileFormat
import io.eia.batchetl.application.usecase.ExportDailySalesFileService
import io.eia.batchetl.domain.BusinessDay
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.filetransfer.verify.Verification
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CurrentTrace
import io.eia.platform.observability.context.withSpan
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.util.HexFormat
import java.util.zip.GZIPInputStream

/**
 * 日次の売上ファイルの書き出し(ADR-0031 §8)を、実際の PostgreSQL と S3 互換ストレージ(SeaweedFS)で確かめる。
 * - mart の行を、注文 ID のバイト順(DB の既定の照合順序が言語的な並びでも)で、Parquet と CSV + gzip に書き、受け側の検査に通る
 * - マニフェストの traceparent は書き出しの span。個人情報(顧客 ID)はファイルに入らない
 * - 同じ版は同じ名前で上書き、版が変われば別の名前。パーティションのない日は書かない。注文のない日は 0 件のファイル
 */
class SalesFileIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val bucket = "eiaf-file-exchange"
        val prefix = "inbox/sales-daily/"
        val seaweed =
            SeaweedFsContainer(
                mapOf(
                    "eiaf" to listOf("Admin", "Read", "Write", "List"),
                    // 送り手は受け口の自分の接頭辞に書くだけ(ADR-0032 §1)
                    "eiaf-file-batch-etl" to listOf("Write:$bucket/$prefix*"),
                    "eiaf-file-exchange" to listOf("Read:$bucket", "List:$bucket"),
                ),
            )
        val runtime = Observability.init(ObservabilityConfig.of("batch-etl-it").getOrNull()!!, TelemetrySinks(), installLogAppender = false)
        val workDir = Files.createTempDirectory("sales-file-it")
        val ownerPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val appPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val day = BusinessDay.parse("2026-10-14").getOrNull()!!
        val accessKey = SecretName("IT_SALES_S3_ACCESS_KEY")
        val secretKey = SecretName("IT_SALES_S3_SECRET_KEY")
        lateinit var app: PGSimpleDataSource

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun execute(
            source: PGSimpleDataSource,
            sql: String,
        ) = source.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun store(identity: String): S3ObjectStore {
            val credentials = seaweed.credentials.getValue(identity)
            return S3ObjectStore(
                S3StoreConfig(URI(seaweed.endpoint), bucket, accessKey, secretKey),
                EnvSecretProvider(mapOf(accessKey.value to credentials.accessKey, secretKey.value to credentials.secretKey)),
            )
        }

        fun service(formats: List<SalesFileFormat>) =
            ExportDailySalesFileService(
                JdbcDailySalesLines(app),
                S3SalesFileStore(FilePublisher(store("eiaf-file-batch-etl"), workDir), prefix),
                formats,
            )

        /** DAG のステップと同じく、span の中で書き出す(マニフェストの traceparent はこの span)。 */
        suspend fun export(
            formats: List<SalesFileFormat>,
            exportDay: BusinessDay,
            revision: Int,
        ) = runtime.withSpan("batch-etl.sales-file export") { service(formats)(exportDay, revision) }

        fun keys(): List<String> = store("eiaf-file-exchange").use { it.list(prefix).getOrNull()!!.map { o -> o.key } }

        beforeSpec {
            postgres.start()
            seaweed.start()
            val superuser = dataSource(postgres.databaseName, postgres.username, postgres.password)
            execute(superuser, "CREATE ROLE batch_etl LOGIN PASSWORD '$ownerPassword'")
            execute(superuser, "CREATE ROLE ${BatchEtlSchema.APP_ROLE} LOGIN PASSWORD '$appPassword'")
            // 既定の照合順序を言語的な並びにして、ORDER BY の照合順序の明示を確かめる(ADR-0031 §3)
            execute(
                superuser,
                "CREATE DATABASE sales_file OWNER batch_etl TEMPLATE template0 LOCALE_PROVIDER icu ICU_LOCALE 'und' LOCALE 'C'",
            )
            BatchEtlSchema.migrate(dataSource("sales_file", "batch_etl", ownerPassword)).getOrNull()!!
            app = dataSource("sales_file", BatchEtlSchema.APP_ROLE, appPassword)
            // 言語的な並びなら a < B < c、バイト順なら B < Z < a < c
            listOf("c-1" to "JP-13", "B-2" to null, "a-3" to "JP-27", "Z-4" to "JP-01").forEach { (id, region) ->
                execute(
                    app,
                    """
                    INSERT INTO mart.order_daily (business_date, order_id, customer_id, ordered_at, status, total_minor_units, currency,
                        line_count, ship_country, ship_region)
                    VALUES ('2026-10-14', '$id', 'customer-secret-$id', '2026-10-14T01:00:00Z', 'PLACED', 1000, 'JPY', 1, 'JP',
                        ${region?.let { "'$it'" } ?: "NULL"})
                    """.trimIndent(),
                )
            }
            execute(app, "INSERT INTO mart.partition_build (business_date, order_count, built_at) VALUES ('2026-10-14', 4, now())")
            execute(app, "INSERT INTO mart.partition_build (business_date, order_count, built_at) VALUES ('2026-10-15', 0, now())")
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
            postgres.stop()
            seaweed.stop()
            workDir.toFile().deleteRecursively()
        }

        test("Parquet と CSV + gzip を書き、受け側の検査に通る。行は注文 ID のバイト順。顧客 ID は入らない。traceparent は書き出しの span") {
            val (report, traceparent) =
                runtime.withSpan("batch-etl.sales-file export") {
                    service(listOf(SalesFileFormat.PARQUET, SalesFileFormat.CSV_GZIP))(day, 1).getOrNull()!! to
                        CurrentTrace.get().traceParent!!.format()
                }

            report.files.map { it.name } shouldBe listOf("sales_daily_20261014150000_001.parquet", "sales_daily_20261014150000_001.csv.gz")
            report.files.map { it.records } shouldBe listOf(4L, 4L)
            store("eiaf-file-exchange").use { exchange ->
                val verifier = FileVerifier(exchange, listOf(SalesDailyLayout.V1), workDir)
                for (file in report.files) {
                    val base = file.name.substringBefore('.')
                    val accepted =
                        verifier.verify(prefix, "$prefix$base.manifest.json").getOrNull().shouldBeInstanceOf<Verification.Accepted>()
                    accepted.manifest.recordCount shouldBe 4
                    accepted.manifest.traceparent shouldBe traceparent
                    accepted.manifest.schemaVersion shouldBe "sales_daily.v1"
                }
                val csvFile = workDir.resolve("sales.csv.gz")
                exchange.download("${prefix}sales_daily_20261014150000_001.csv.gz", csvFile).getOrNull() shouldBe true
                val text = GZIPInputStream(Files.newInputStream(csvFile)).readAllBytes().decodeToString()
                text
                    .lines()
                    .drop(1)
                    .filter { it.isNotEmpty() }
                    .map { it.split(',')[1] } shouldContainExactly
                    listOf("B-2", "Z-4", "a-3", "c-1")
                text.contains("customer-secret") shouldBe false
                // RFC 4180: 改行は CRLF
                text.startsWith(
                    "business_date,order_id,ordered_at,status,total_minor_units,currency,ship_country,ship_region\r\n",
                ) shouldBe true
            }
        }

        test("span の外(traceparent がない)では書き出さない") {
            service(listOf(SalesFileFormat.PARQUET))(day, 9).shouldBeInstanceOf<Result.Err<*>>()
            keys().none { it.contains("_009.") } shouldBe true
        }

        test("同じ版は同じ名前で上書きし、版が変われば別の名前で置く") {
            export(listOf(SalesFileFormat.PARQUET), day, 1).getOrNull()!!
            export(listOf(SalesFileFormat.PARQUET), day, 2).getOrNull()!!
            keys().filter { it.endsWith(".parquet") } shouldContainExactly
                listOf("${prefix}sales_daily_20261014150000_001.parquet", "${prefix}sales_daily_20261014150000_002.parquet")
            store("eiaf-file-exchange").use { exchange ->
                val manifest = exchange.read("${prefix}sales_daily_20261014150000_002.manifest.json", FileManifest.MAX_BYTES).getOrNull()!!
                FileManifest.parse(manifest).getOrNull()!!.recordCount shouldBe 4
            }
        }

        test("注文のない日は 0 件のファイル。パーティションがまだない日は書かない") {
            val empty = BusinessDay.parse("2026-10-15").getOrNull()!!
            export(listOf(SalesFileFormat.PARQUET), empty, 1)
                .getOrNull()!!
                .files
                .single()
                .records shouldBe 0

            val before = keys()
            val missing = BusinessDay.parse("2026-10-16").getOrNull()!!
            val result = export(listOf(SalesFileFormat.PARQUET), missing, 1)
            result.shouldBeInstanceOf<Result.Err<*>>().error.shouldBeInstanceOf<ConflictError>()
            keys() shouldBe before
        }
    })
