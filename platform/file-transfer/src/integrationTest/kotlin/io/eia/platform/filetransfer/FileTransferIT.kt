@file:Suppress("MagicNumber") // テストデータの件数

package io.eia.platform.filetransfer

import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileColumn
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.move.FileMover
import io.eia.platform.filetransfer.move.QuarantineReason
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.store.StoredObject
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.filetransfer.verify.RejectionReason
import io.eia.platform.filetransfer.verify.Verification
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.filetransfer.write.PublishRequest
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * ファイル連携の部品を、実際の S3 互換ストレージ(SeaweedFS)で確かめる(ADR-0032)。
 * - 書き手(`eiaf-file-writer`)は受け口(`inbox/`)に書くだけ。受け手(`eiaf-file-exchange`)は読み取り・移動。監視(`eiaf-file-watcher`)は読むだけ
 * - 書いた Parquet を、別の実装(DuckDB)で読み直す(ADR-0033 §4)
 * - 移動のどの手順の間で落ちても、やり直せば同じ結果に収まる。データファイルを移した後・マニフェストを移す前に落ちても、
 *   移動の決定を先に記録しておけば(file-exchange の received_file の代わりに [Receipts])、quarantine に送らずに archive に収まる
 */
class FileTransferIT :
    FunSpec({
        val bucket = "eiaf-file-exchange"
        val seaweed =
            SeaweedFsContainer(
                mapOf(
                    "eiaf" to listOf("Admin", "Read", "Write", "List"),
                    "eiaf-file-writer" to listOf("Write:$bucket/inbox/*"),
                    "eiaf-file-exchange" to listOf("Read:$bucket", "List:$bucket", "Write:$bucket"),
                    "eiaf-file-watcher" to listOf("Read:$bucket", "List:$bucket"),
                ),
            )
        val workDir = Files.createTempDirectory("file-transfer-it")
        lateinit var admin: S3Client
        val accessKey = SecretName("IT_FILE_S3_ACCESS_KEY")
        val secretKey = SecretName("IT_FILE_S3_SECRET_KEY")
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val createdAt = Instant.parse("2026-10-14T16:00:00Z")
        val layout =
            FileLayout
                .of(
                    "sales",
                    "daily",
                    1,
                    listOf(
                        FileColumn("business_date", ColumnType.DATE, nullable = false),
                        FileColumn("order_id", ColumnType.STRING, nullable = false),
                        FileColumn("ordered_at", ColumnType.TIMESTAMP, nullable = false),
                        FileColumn("total_minor_units", ColumnType.INT64, nullable = false),
                        FileColumn("ship_region", ColumnType.STRING, nullable = true),
                    ),
                ).ok()
        val sequence = AtomicInteger()

        fun store(identity: String): S3ObjectStore {
            val credentials = seaweed.credentials.getValue(identity)
            return S3ObjectStore(
                S3StoreConfig(URI(seaweed.endpoint), bucket, accessKey, secretKey),
                EnvSecretProvider(mapOf(accessKey.value to credentials.accessKey, secretKey.value to credentials.secretKey)),
            )
        }

        fun rows(count: Int): Sequence<List<Any?>> =
            (1..count).asSequence().map {
                listOf(
                    LocalDate.of(2026, 10, 14),
                    "order-%05d".format(it),
                    java.time.Instant
                        .parse("2026-10-14T00:00:00Z")
                        .plusSeconds(it.toLong()),
                    100L * it,
                    if (it % 10 == 0) null else "JP-13",
                )
            }

        /** 受け口に新しい名前でファイルを置く(書き手の identity)。 */
        fun publish(
            format: FileFormat = FileFormat.PARQUET,
            count: Int = 100,
        ): Pair<String, String> {
            val name = FileName.of(layout, createdAt, sequence.incrementAndGet(), format).ok()
            val published =
                store("eiaf-file-writer").use { writer ->
                    FilePublisher(
                        writer,
                        workDir,
                    ).publish(PublishRequest("inbox/", layout, name, traceparent, "corr-it", createdAt), rows(count))
                }
            return name.base to published.ok().manifestKey
        }

        fun keysUnder(
            exchange: ObjectStore,
            prefix: String,
            base: String,
        ): List<String> =
            exchange
                .list(prefix)
                .ok()
                .map { it.key }
                .filter { it.startsWith("$prefix$base.") }

        beforeSpec {
            seaweed.start()
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
            admin.close()
            seaweed.stop()
            workDir.toFile().deleteRecursively()
        }

        test("置き場: 一覧はキーの順。ないキーは null・false。コピー・削除は繰り返して害がない。大きすぎるオブジェクトは上限 + 1 バイトまで読む") {
            store("eiaf-file-exchange").use { exchange ->
                exchange.put("ops/b.txt", "b".toByteArray()).ok()
                exchange.put("ops/a.txt", ByteArray(FileManifest.MAX_BYTES + 10)).ok()

                exchange.list("ops/").ok() shouldBe
                    listOf(StoredObject("ops/a.txt", FileManifest.MAX_BYTES + 10L), StoredObject("ops/b.txt", 1))
                exchange.read("ops/a.txt", FileManifest.MAX_BYTES).ok()!!.size shouldBe FileManifest.MAX_BYTES + 1
                exchange.read("ops/none.txt", 10).ok().shouldBeNull()
                exchange.head("ops/b.txt").ok() shouldBe StoredObject("ops/b.txt", 1)
                exchange.head("ops/none.txt").ok().shouldBeNull()
                val target = workDir.resolve("download.txt")
                exchange.download("ops/b.txt", target).ok() shouldBe true
                Files.readString(target) shouldBe "b"
                exchange.download("ops/none.txt", workDir.resolve("none.txt")).ok() shouldBe false

                exchange.copy("ops/b.txt", "ops/c.txt").ok() shouldBe true
                exchange.copy("ops/none.txt", "ops/d.txt").ok() shouldBe false
                exchange.delete("ops/c.txt").ok()
                exchange.delete("ops/c.txt").ok()
                exchange.upload("ops/e.txt", target).ok()
                exchange.list("ops/").ok().map { it.key } shouldBe listOf("ops/a.txt", "ops/b.txt", "ops/e.txt")
            }
        }

        test("権限: 書き手は受け口の外に書けず、監視は書けない(拒否・リトライしない)。資格情報がなければ一時的な失敗") {
            store("eiaf-file-writer").use { it.put("archive/x.txt", "x".toByteArray()).err() shouldBe StoreRejected("AccessDenied") }
            store("eiaf-file-watcher").use { it.put("inbox/x.txt", "x".toByteArray()).err() shouldBe StoreRejected("AccessDenied") }
            S3ObjectStore(S3StoreConfig(URI(seaweed.endpoint), bucket, accessKey, secretKey), EnvSecretProvider(emptyMap())).use {
                it.list("").err().shouldBeInstanceOf<StoreUnavailable>()
            }
        }

        test("書き出し → 検査 → archive。archive の Parquet を DuckDB で読むと、件数・合計・null の数が一致する") {
            val (base, manifestKey) = publish(FileFormat.PARQUET, count = 1_000)
            store("eiaf-file-exchange").use { exchange ->
                val accepted =
                    FileVerifier(exchange, listOf(layout), workDir)
                        .verify("inbox/", manifestKey)
                        .ok()
                        .shouldBeInstanceOf<Verification.Accepted>()
                accepted.manifest.recordCount shouldBe 1_000
                accepted.manifest.traceparent shouldBe traceparent
                FileMover(exchange).archive("inbox/", base, accepted.manifest.file, "archive/").ok()

                keysUnder(exchange, "inbox/", base).shouldBeEmpty()
                keysUnder(exchange, "archive/", base) shouldContainExactly listOf("archive/$base.manifest.json", "archive/$base.parquet")

                val local = workDir.resolve("$base.parquet")
                exchange.download("archive/$base.parquet", local).ok() shouldBe true
                DriverManager.getConnection("jdbc:duckdb:").use { duckdb ->
                    duckdb
                        .prepareStatement(
                            "SELECT count(*), sum(total_minor_units), count(*) - count(ship_region) FROM read_parquet(?)",
                        ).use {
                            it.setString(1, local.toString())
                            it.executeQuery().use { result ->
                                result.next()
                                result.getLong(1) shouldBe 1_000
                                result.getLong(2) shouldBe (1..1_000).sumOf { n -> 100L * n }
                                result.getLong(3) shouldBe 100
                            }
                        }
                }
            }
        }

        test("CSV + gzip も検査に通り、改竄したファイルは quarantine に理由と一緒に移る") {
            val (base, manifestKey) = publish(FileFormat.CSV_GZIP, count = 50)
            store("eiaf-file-exchange").use { exchange ->
                FileVerifier(
                    exchange,
                    listOf(layout),
                    workDir,
                ).verify("inbox/", manifestKey).ok().shouldBeInstanceOf<Verification.Accepted>()

                // 受け口のデータファイルを差し替える(大きさは同じ)
                val dataKey = "inbox/$base.csv.gz"
                val local = workDir.resolve("tamper.csv.gz")
                exchange.download(dataKey, local).ok()
                val bytes = Files.readAllBytes(local).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                exchange.put(dataKey, bytes).ok()

                val rejected =
                    FileVerifier(exchange, listOf(layout), workDir)
                        .verify("inbox/", manifestKey)
                        .ok()
                        .shouldBeInstanceOf<Verification.Rejected>()
                rejected.reason shouldBe RejectionReason.CHECKSUM_MISMATCH
                FileMover(exchange)
                    .quarantine("inbox/", base, "quarantine/", QuarantineReason(rejected.reason, rejected.detail, createdAt, "corr-it"))
                    .ok()
                keysUnder(exchange, "quarantine/", base) shouldContainExactly
                    listOf("quarantine/$base.csv.gz", "quarantine/$base.manifest.json", "quarantine/$base.reason.json")
                keysUnder(exchange, "inbox/", base).shouldBeEmpty()
            }
        }

        test("移動のどの手順の間で落ちても、やり直せば archive に収まる。マニフェストだけが残った場合も quarantine に送らない") {
            // 合格のファイルの移動の書き込みは 4 回(データファイルのコピー・削除、マニフェストのコピー・削除)
            for (crashBefore in 1..4) {
                val (base, manifestKey) = publish()
                val receipts = Receipts()
                store("eiaf-file-exchange").use { exchange ->
                    val crashing = CrashingStore(exchange, crashBefore)
                    receive(crashing, layout, workDir, receipts, manifestKey).err() shouldBe StoreUnavailable("crash")
                    val inboxAfterCrash = keysUnder(exchange, "inbox/", base)

                    if (crashBefore == 3) {
                        // データファイルを移した後・マニフェストを移す前: 受け口にはマニフェストだけ。検査からやり直すと欠損と判定してしまう
                        inboxAfterCrash shouldContainExactly listOf(manifestKey)
                        FileVerifier(exchange, listOf(layout), workDir)
                            .verify("inbox/", manifestKey)
                            .ok()
                            .shouldBeInstanceOf<Verification.Rejected>()
                            .reason shouldBe RejectionReason.DATA_MISSING
                    }

                    // 再起動: 記録した決定(archive)の移動を、検査をせずに続ける
                    receive(exchange, layout, workDir, receipts, manifestKey).ok() shouldBe Destination.ARCHIVE
                    keysUnder(exchange, "archive/", base) shouldContainExactly
                        listOf("archive/$base.manifest.json", "archive/$base.parquet")
                    keysUnder(exchange, "inbox/", base).shouldBeEmpty()
                    keysUnder(exchange, "quarantine/", base).shouldBeEmpty()
                }
            }
        }

        test("不合格のファイルも、移動のどの手順の間で落ちても、やり直せば quarantine に収まる") {
            // 不合格のファイルの移動の書き込みは 5 回(データファイルのコピー・削除、理由、マニフェストのコピー・削除)
            for (crashBefore in 1..5) {
                val (base, manifestKey) = publish()
                store("eiaf-file-exchange").use { exchange ->
                    // 件数を偽ったマニフェスト(SHA-256 は合っている)
                    val manifest = FileManifest.parse(exchange.read(manifestKey, FileManifest.MAX_BYTES).ok()!!).ok()
                    exchange.put(manifestKey, manifest.copy(recordCount = 99).toJson()).ok()
                    val receipts = Receipts()
                    receive(CrashingStore(exchange, crashBefore), layout, workDir, receipts, manifestKey).err() shouldBe
                        StoreUnavailable("crash")

                    receive(exchange, layout, workDir, receipts, manifestKey).ok() shouldBe Destination.QUARANTINE
                    keysUnder(exchange, "quarantine/", base) shouldContainExactly
                        listOf("quarantine/$base.manifest.json", "quarantine/$base.parquet", "quarantine/$base.reason.json")
                    keysUnder(exchange, "inbox/", base).shouldBeEmpty()
                    keysUnder(exchange, "archive/", base).shouldBeEmpty()
                }
            }
        }
    })

private enum class Destination { ARCHIVE, QUARANTINE }

/** file-exchange の received_file(⑤)の代わり。移動を始める前に、どちらへ移すか(と、移すファイル・理由)を記録する。 */
private class Receipts {
    val decided = mutableMapOf<String, Decision>()

    data class Decision(
        val destination: Destination,
        val dataFile: String?,
        val reason: QuarantineReason?,
    )
}

/**
 * 受け取りの手順(⑤ の file-exchange が DB で行うことの模型。ADR-0032 §5)。
 * 1. 記録があれば検査をせずに、記録した移動を続ける(移動の途中で落ちた後の再開)
 * 2. なければ検査して、移動の決定を記録してから移す
 */
private fun receive(
    store: ObjectStore,
    layout: FileLayout,
    workDir: Path,
    receipts: Receipts,
    manifestKey: String,
): Result<Destination, FileTransferError> {
    val base = manifestKey.removePrefix("inbox/").removeSuffix(FileName.MANIFEST_SUFFIX)
    val decision =
        receipts.decided[base] ?: when (val verified = FileVerifier(store, listOf(layout), workDir).verify("inbox/", manifestKey)) {
            is Result.Err -> {
                return verified
            }

            is Result.Ok -> {
                when (val verification = verified.value) {
                    is Verification.Accepted -> {
                        Receipts.Decision(Destination.ARCHIVE, verification.manifest.file, null)
                    }

                    is Verification.Rejected -> {
                        Receipts.Decision(
                            Destination.QUARANTINE,
                            null,
                            QuarantineReason(verification.reason, verification.detail, Instant.parse("2026-10-15T00:00:00Z"), null),
                        )
                    }
                }
            }
        }.also { receipts.decided[base] = it }
    val mover = FileMover(store)
    val moved =
        when (decision.destination) {
            Destination.ARCHIVE -> mover.archive("inbox/", base, decision.dataFile!!, "archive/")
            Destination.QUARANTINE -> mover.quarantine("inbox/", base, "quarantine/", decision.reason!!)
        }
    return when (moved) {
        is Result.Err -> moved
        is Result.Ok -> ok(decision.destination)
    }
}

/** [crashBefore] 回目の書き込みの直前で失敗する(プロセスが落ちたのと同じく、その後は何もしない)置き場。 */
private class CrashingStore(
    private val delegate: ObjectStore,
    private val crashBefore: Int,
) : ObjectStore by delegate {
    private var writes = 0

    private fun <T> guarded(write: () -> Result<T, FileTransferError>): Result<T, FileTransferError> =
        if (++writes >= crashBefore) err(StoreUnavailable("crash")) else write()

    override fun upload(
        key: String,
        source: Path,
    ): Result<Unit, FileTransferError> = guarded { delegate.upload(key, source) }

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Result<Unit, FileTransferError> = guarded { delegate.put(key, bytes) }

    override fun copy(
        sourceKey: String,
        targetKey: String,
    ): Result<Boolean, FileTransferError> =
        // コピー元がないコピーは書き込みに数えない(InMemoryObjectStore と同じ)
        if (delegate.head(sourceKey).let {
                it is Result.Ok && it.value == null
            }
        ) {
            delegate.copy(sourceKey, targetKey)
        } else {
            guarded { delegate.copy(sourceKey, targetKey) }
        }

    override fun delete(key: String): Result<Unit, FileTransferError> = guarded { delegate.delete(key) }
}
