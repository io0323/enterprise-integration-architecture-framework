@file:Suppress("MagicNumber") // テストデータの件数・繰り返しの回数

package io.eia.fileexchange.adapters

import io.eia.fileexchange.adapters.file.InboxPrefixes
import io.eia.fileexchange.adapters.file.S3Inbox
import io.eia.fileexchange.adapters.file.SalesDailyLayout
import io.eia.fileexchange.adapters.persistence.BatchFileLocks
import io.eia.fileexchange.adapters.persistence.FileExchangeSchema
import io.eia.fileexchange.adapters.persistence.JdbcReceivedFiles
import io.eia.fileexchange.application.usecase.ReceiveFileService
import io.eia.fileexchange.application.usecase.SweepInboxService
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.platform.batch.DagId
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.filetransfer.write.PublishRequest
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

/**
 * 受け取り(検査・移動の決定の記録・移動)と見回り(受け取り済みの名前で届いたファイル)を、実際の PostgreSQL と S3 互換ストレージ
 * (SeaweedFS)で確かめる(ADR-0032 §4・§5)。見回りと受け取りの実行が同時に動いても、同じファイルを扱うのはどちらか一方だけ。
 */
class FileExchangeIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val bucket = "eiaf-file-exchange"
        val prefixes = InboxPrefixes("inbox/sales-daily/", "archive/sales-daily/", "quarantine/sales-daily/")
        val seaweed =
            SeaweedFsContainer(
                mapOf(
                    "eiaf" to listOf("Admin", "Read", "Write", "List"),
                    "eiaf-file-batch-etl" to listOf("Write:$bucket/${prefixes.inbox}*"),
                    "eiaf-file-exchange" to listOf("Read:$bucket", "List:$bucket", "Write:$bucket"),
                ),
            )
        val workDir = Files.createTempDirectory("file-exchange-it")
        val ownerPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val appPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val receiveDag = DagId("file-exchange.receive")
        val sequence = AtomicInteger()
        lateinit var app: PGSimpleDataSource
        lateinit var admin: S3Client

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun store(identity: String): S3ObjectStore {
            val credentials = seaweed.credentials.getValue(identity)
            return S3ObjectStore(
                S3StoreConfig(URI(seaweed.endpoint), bucket, SecretName("A"), SecretName("S")),
                EnvSecretProvider(mapOf("A" to credentials.accessKey, "S" to credentials.secretKey)),
            )
        }

        val exchange by lazy { store("eiaf-file-exchange") }
        val files by lazy { JdbcReceivedFiles(app) }
        val locks by lazy { BatchFileLocks(JdbcBatchStore(app), receiveDag) }
        val inbox by lazy { S3Inbox(exchange, FileVerifier(exchange, listOf(SalesDailyLayout.V1), workDir), prefixes) }
        val receive by lazy { ReceiveFileService(inbox, files) }
        val sweep by lazy { SweepInboxService(inbox, files, locks) }

        fun rows(count: Int): Sequence<List<Any?>> =
            (1..count).asSequence().map {
                listOf(
                    LocalDate.of(2026, 10, 14),
                    "order-%04d".format(it),
                    java.time.Instant
                        .parse("2026-10-14T01:00:00Z")
                        .plusSeconds(it.toLong()),
                    "PLACED",
                    100L * it,
                    "JPY",
                    "JP",
                    "JP-13",
                )
            }

        /** 送り手(受け口に書くだけの identity)として売上ファイルを置き、名前を返す。[seq] を渡すと同じ名前で置き直す。 */
        fun publish(
            count: Int = 5,
            seq: Int = sequence.incrementAndGet(),
        ): String {
            val name = FileName.of(SalesDailyLayout.V1, Instant.parse("2026-10-14T15:00:00Z"), seq, FileFormat.PARQUET).ok()
            store("eiaf-file-batch-etl").use { writer ->
                FilePublisher(writer, workDir)
                    .publish(
                        PublishRequest(
                            prefixes.inbox,
                            SalesDailyLayout.V1,
                            name,
                            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                            "corr",
                            Instant.parse("2026-10-14T16:00:00Z"),
                        ),
                        rows(count),
                    ).ok()
            }
            return name.base
        }

        fun keys(prefix: String): List<String> = exchange.list(prefix).ok().map { it.key }

        fun keysOf(
            prefix: String,
            name: String,
        ): List<String> = keys(prefix).filter { it.removePrefix(prefix).startsWith("$name.") }

        /** 受け取りの実行と同じく、ロックの中で受け取る。 */
        suspend fun receiveLocked(name: String) = locks.withLock(name) { receive(name) }

        beforeSpec {
            postgres.start()
            seaweed.start()
            val superuser = dataSource(postgres.databaseName, postgres.username, postgres.password)
            superuser.connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE file_exchange LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE ${FileExchangeSchema.APP_ROLE} LOGIN PASSWORD '$appPassword'")
                    s.execute("CREATE DATABASE file_exchange OWNER file_exchange")
                }
            }
            FileExchangeSchema.migrate(dataSource("file_exchange", "file_exchange", ownerPassword)).ok()
            app = dataSource("file_exchange", FileExchangeSchema.APP_ROLE, appPassword)
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
            admin.createBucket { it.bucket(bucket) }
        }

        afterSpec {
            exchange.close()
            admin.close()
            seaweed.stop()
            postgres.stop()
            workDir.toFile().deleteRecursively()
        }

        test("合格は archive、欠損・改竄は quarantine(理由のファイルつき)。どれも移し終えたと記録する") {
            val good = publish()
            val missing = publish().also { exchange.delete("${prefixes.inbox}$it.parquet").ok() }
            val tampered = publish()
            exchange.put("${prefixes.inbox}$tampered.parquet", "tampered".toByteArray()).ok()

            receiveLocked(good).ok()!!.file.destination shouldBe ReceiptDestination.ARCHIVE
            receiveLocked(missing).ok()!!.file.reason shouldBe "data_missing"
            receiveLocked(tampered).ok()!!.file.reason shouldBe "size_mismatch"

            keysOf(prefixes.archive, good) shouldContainExactly
                listOf("${prefixes.archive}$good.manifest.json", "${prefixes.archive}$good.parquet")
            keysOf(prefixes.quarantine, missing) shouldContainExactly
                listOf("${prefixes.quarantine}$missing.manifest.json", "${prefixes.quarantine}$missing.reason.json")
            keysOf(prefixes.quarantine, tampered).size shouldBe 3
            keys(prefixes.inbox).filter { key -> listOf(good, missing, tampered).any { key.contains(it) } }.shouldBeEmpty()
            listOf(good, missing, tampered).map { files.find(it).ok()!!.state }.toSet() shouldBe setOf(ReceiptState.STORED)
        }

        test("移動の決定を記録した後に落ちた(データファイルだけ移した)ら、検査をせずに続きから archive に収める") {
            val name = publish()
            val sha = inbox.waiting(name).ok()!!.sha256
            files.begin(ReceivedFile(name, sha, ReceiptDestination.ARCHIVE, "$name.parquet", null, null, ReceiptState.MOVING)).ok()
            // 落ちる前の移動: データファイルをコピーして消した。受け口にはマニフェストだけ
            exchange.copy("${prefixes.inbox}$name.parquet", "${prefixes.archive}$name.parquet").ok()
            exchange.delete("${prefixes.inbox}$name.parquet").ok()

            val outcome = receiveLocked(name).ok()!!
            outcome.resumed shouldBe true
            outcome.file.destination shouldBe ReceiptDestination.ARCHIVE
            keysOf(prefixes.archive, name).size shouldBe 2
            keysOf(prefixes.quarantine, name).shouldBeEmpty()
        }

        test("見回り: 受け取り済みの名前で同じ中身なら送り直し、違えば名前の使い回しとして、番号ごとの場所に移す。前のものは残す") {
            val name = publish(count = 3)
            val seq = name.substringAfterLast('_').toInt()
            receiveLocked(name).ok()

            publish(count = 3, seq = seq)
            val first = sweep().ok()
            first.moved.single().reason shouldBe ExtraArrivalReason.DUPLICATE
            publish(count = 4, seq = seq)
            val second = sweep().ok()
            second.moved.single().reason shouldBe ExtraArrivalReason.NAME_REUSED

            keysOf(prefixes.archive, name).size shouldBe 2
            keys("${prefixes.quarantine}extra/${first.moved.single().id}/").size shouldBe 3
            keys("${prefixes.quarantine}extra/${second.moved.single().id}/").size shouldBe 3
            keysOf(prefixes.inbox, name).shouldBeEmpty()
        }

        test("見回りはまだ受け取っていないファイルに触れない。受け取りの実行と同時に動いても、扱うのはどちらか一方だけ") {
            repeat(10) {
                val name = publish()
                coroutineScope {
                    val sweeps = (1..3).map { async { sweep().ok() } }
                    val received = async { receiveLocked(name).ok() }
                    sweeps.awaitAll().flatMap { it.moved }.none { it.name == name } shouldBe true
                    // ロックを見回りに取られていたら、受け取りの実行は次の起動でやり直す(スケジューラと同じ)
                    received.await() ?: receiveLocked(name).ok()
                }
                files.find(name).ok()!!.state shouldBe ReceiptState.STORED
                keysOf(prefixes.archive, name).size shouldBe 2
                keys("${prefixes.quarantine}extra/").none { it.contains(name) } shouldBe true
            }
        }

        test("受け取り済みの名前で届いたファイルを、見回りが同時に 2 つ動いても 1 度だけ移す") {
            val name = publish()
            receiveLocked(name).ok()
            publish(seq = name.substringAfterLast('_').toInt())

            val moved = coroutineScope { (1..4).map { async { sweep().ok().moved } }.awaitAll().flatten() }
            moved.map { it.name } shouldBe listOf(name)
            keys("${prefixes.quarantine}extra/${moved.single().id}/").size shouldBe 3
            app.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM exchange.extra_arrival WHERE file_name = '$name'").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }

        test("受け取りの記録: 同じ名前は 2 つ記録できない(先の決定を返す)。処理中の重複も名前ごとに 1 つだけ") {
            val first =
                ReceivedFile(
                    "dup_x_20261014150000_001",
                    null,
                    ReceiptDestination.QUARANTINE,
                    null,
                    "manifest_invalid",
                    "x",
                    ReceiptState.MOVING,
                )
            files.begin(first).ok() shouldBe first
            files
                .begin(
                    first.copy(destination = ReceiptDestination.ARCHIVE, dataFile = "y.parquet", reason = null, detail = null),
                ).ok() shouldBe
                first

            val a = files.beginExtra("dup_x_20261014150000_001", null, ExtraArrivalReason.NAME_REUSED).ok()
            files.beginExtra("dup_x_20261014150000_001", "a".repeat(64), ExtraArrivalReason.DUPLICATE).ok() shouldBe a
            files.finishExtra(a.id).ok()
            // 終えた後は、新しい番号で始める(ON CONFLICT DO NOTHING でも連番は進むので、番号は飛びうる)
            (files.beginExtra("dup_x_20261014150000_001", null, ExtraArrivalReason.NAME_REUSED).ok().id > a.id) shouldBe true
        }
    })
