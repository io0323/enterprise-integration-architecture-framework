package io.eia.platform.filetransfer.verify

import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.Fixtures
import io.eia.platform.filetransfer.InMemoryObjectStore
import io.eia.platform.filetransfer.ObjectMissing
import io.eia.platform.filetransfer.StoreUnavailable
import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.ok
import io.eia.platform.filetransfer.write.DataFiles
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.filetransfer.write.PublishedFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class FileVerifierSpec :
    FunSpec({
        val workDir = Files.createTempDirectory("file-verifier-spec")

        afterSpec { workDir.toFile().deleteRecursively() }

        fun verifier(store: InMemoryObjectStore) = FileVerifier(store, listOf(Fixtures.LAYOUT), workDir)

        fun publish(
            store: InMemoryObjectStore,
            format: FileFormat,
            count: Int = 10,
            layout: FileLayout = Fixtures.LAYOUT,
            rows: Sequence<List<Any?>> = Fixtures.rows(count),
        ): PublishedFile = FilePublisher(store, workDir).publish(Fixtures.request(format, layout = layout), rows).ok()

        /** マニフェストを書き換える(送り手が誤ったマニフェストを置いた、または改竄された)。 */
        fun rewriteManifest(
            store: InMemoryObjectStore,
            published: PublishedFile,
            change: (FileManifest) -> FileManifest,
        ) {
            store.objects[published.manifestKey] = change(published.manifest).toJson()
        }

        fun rejection(
            store: InMemoryObjectStore,
            published: PublishedFile,
        ): Verification.Rejected =
            verifier(store).verify(Fixtures.INBOX, published.manifestKey).ok().shouldBeInstanceOf<Verification.Rejected>()

        FileFormat.entries.forEach { format ->
            test("合格: 書き出したファイルは検査に通る($format)") {
                val store = InMemoryObjectStore()
                val published = publish(store, format, count = 37)

                val accepted =
                    verifier(store)
                        .verify(Fixtures.INBOX, published.manifestKey)
                        .ok()
                        .shouldBeInstanceOf<Verification.Accepted>()
                accepted.base shouldBe "sales_daily_20261014160000_001"
                accepted.dataKey shouldBe published.dataKey
                accepted.manifest shouldBe published.manifest
                // 検査はファイルを動かさない
                store.objects.keys shouldBe setOf(published.dataKey, published.manifestKey)
            }
        }

        FileFormat.entries.forEach { format ->
            test("不合格: 改竄・欠損・大きさ・件数・スキーマの版・名前の対応($format)") {
                val store = InMemoryObjectStore()

                // データファイルの改竄(1 バイト変える。大きさは同じ)
                publish(store, format).let { published ->
                    val data = store.objects.getValue(published.dataKey)
                    data[data.size / 2] = (data[data.size / 2] + 1).toByte()
                    rejection(store, published).reason shouldBe RejectionReason.CHECKSUM_MISMATCH
                }
                // データファイルの欠損
                publish(store, format).let { published ->
                    store.objects.remove(published.dataKey)
                    rejection(store, published).reason shouldBe RejectionReason.DATA_MISSING
                }
                // 大きさの違い(途中で切れた転送)
                publish(store, format).let { published ->
                    store.objects[published.dataKey] = store.objects.getValue(published.dataKey).copyOf(10)
                    rejection(store, published).reason shouldBe RejectionReason.SIZE_MISMATCH
                }
                // 件数の違い(SHA-256 は合っている)
                publish(store, format).let { published ->
                    rewriteManifest(store, published) { it.copy(recordCount = 11) }
                    rejection(store, published).reason shouldBe RejectionReason.RECORD_COUNT_MISMATCH
                }
                // 知らないスキーマの版
                publish(store, format).let { published ->
                    rewriteManifest(store, published) { it.copy(schemaVersion = "sales_daily.v2") }
                    rejection(store, published).reason shouldBe RejectionReason.SCHEMA_VERSION_UNKNOWN
                }
                // 名前の対応の誤り
                publish(store, format).let { published ->
                    rewriteManifest(store, published) { it.copy(file = "sales_daily_20261014160000_002.${format.extension}") }
                    rejection(store, published).reason shouldBe RejectionReason.NAME_MISMATCH
                }
            }
        }

        test("マニフェストが読めない・形式の誤り・知らない拡張子") {
            val store = InMemoryObjectStore()
            val published = publish(store, FileFormat.PARQUET)

            store.objects[published.manifestKey] = "not json".toByteArray()
            rejection(store, published).let {
                it.reason shouldBe RejectionReason.MANIFEST_INVALID
                it.manifest shouldBe null
            }
            rewriteManifest(store, published) { it.copy(file = "sales_daily_20261014160000_001.xlsx") }
            rejection(store, published).reason shouldBe RejectionReason.FORMAT_UNSUPPORTED
        }

        test("Parquet: 列が契約と違う(型・nullable・名前)と、壊れたファイル") {
            val store = InMemoryObjectStore()
            val otherTypes =
                FileLayout
                    .of(
                        "sales",
                        "daily",
                        1,
                        Fixtures.COLUMNS.map { if (it.name == "lines") it.copy(type = ColumnType.INT64) else it },
                    ).ok()
            val rows = Fixtures.rows(3).map { row -> row.toMutableList().also { it[5] = (it[5] as Int).toLong() } }
            rejection(store, publish(store, FileFormat.PARQUET, layout = otherTypes, rows = rows)).let {
                it.reason shouldBe RejectionReason.SCHEMA_MISMATCH
                it.detail shouldContain "lines の物理型"
            }

            val nullable = FileLayout.of("sales", "daily", 1, Fixtures.COLUMNS.map { it.copy(nullable = true) }).ok()
            rejection(store, publish(store, FileFormat.PARQUET, layout = nullable)).detail shouldContain "business_date の nullable"

            val renamed =
                FileLayout.of("sales", "daily", 1, Fixtures.COLUMNS.map { if (it.name == "gift") it.copy(name = "is_gift") else it }).ok()
            rejection(store, publish(store, FileFormat.PARQUET, layout = renamed)).detail shouldContain "列の名前・順序"

            // 壊れたファイル(SHA-256 と大きさはマニフェストと合っている)
            val published = publish(store, FileFormat.PARQUET)
            val garbage = ByteArray(100) { it.toByte() }
            store.objects[published.dataKey] = garbage
            rewriteManifest(store, published) {
                it.copy(sha256 = DataFiles.sha256(garbage.inputStream()), sizeBytes = garbage.size.toLong())
            }
            rejection(store, published).reason shouldBe RejectionReason.CONTENT_UNREADABLE
        }

        test("CSV: ヘッダが契約と違う・囲みが閉じていない・gzip でない") {
            val store = InMemoryObjectStore()

            fun replaceCsv(
                published: PublishedFile,
                bytes: ByteArray,
            ) {
                store.objects[published.dataKey] = bytes
                rewriteManifest(
                    store,
                    published,
                ) { it.copy(sha256 = DataFiles.sha256(bytes.inputStream()), sizeBytes = bytes.size.toLong()) }
            }

            fun gzip(text: String): ByteArray =
                ByteArrayOutputStream()
                    .also {
                        GZIPOutputStream(it).use { gz -> gz.write(text.toByteArray()) }
                    }.toByteArray()

            publish(store, FileFormat.CSV_GZIP, count = 1).let { published ->
                replaceCsv(published, gzip("a,b\r\n1,2\r\n"))
                rejection(store, published).reason shouldBe RejectionReason.SCHEMA_MISMATCH
            }
            publish(store, FileFormat.CSV_GZIP, count = 1).let { published ->
                val header = Fixtures.COLUMNS.joinToString(",") { it.name }
                replaceCsv(published, gzip("$header\r\n\"open,2\r\n"))
                rejection(store, published).reason shouldBe RejectionReason.CONTENT_UNREADABLE
            }
            publish(store, FileFormat.CSV_GZIP, count = 1).let { published ->
                replaceCsv(published, "plain text".toByteArray())
                rejection(store, published).reason shouldBe RejectionReason.CONTENT_UNREADABLE
            }
        }

        test("マニフェストがない・キーが受け口の外は失敗。置き場が使えなければその失敗") {
            val store = InMemoryObjectStore()
            verifier(store).verify(Fixtures.INBOX, "inbox/none.manifest.json").err() shouldBe ObjectMissing("inbox/none.manifest.json")
            verifier(store).verify(Fixtures.INBOX, "other/x.manifest.json").err().shouldBeInstanceOf<FileTransferMisuse>()
            verifier(store).verify(Fixtures.INBOX, "inbox/x.csv").err().shouldBeInstanceOf<FileTransferMisuse>()
            store.failing = StoreUnavailable("SlowDown")
            verifier(store).verify(Fixtures.INBOX, "inbox/x.manifest.json").err() shouldBe StoreUnavailable("SlowDown")
        }
    })
