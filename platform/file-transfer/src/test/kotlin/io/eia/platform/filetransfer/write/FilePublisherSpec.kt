package io.eia.platform.filetransfer.write

import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.Fixtures
import io.eia.platform.filetransfer.InMemoryObjectStore
import io.eia.platform.filetransfer.StoreUnavailable
import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.ok
import io.eia.platform.filetransfer.parquet.FooterResult
import io.eia.platform.filetransfer.parquet.ParquetFiles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class FilePublisherSpec :
    FunSpec({
        val workDir = Files.createTempDirectory("file-publisher-spec")

        afterSpec { workDir.toFile().deleteRecursively() }

        test("Parquet: データファイルを置いてから、最後にマニフェストを置く。件数・SHA-256・大きさ・traceparent をマニフェストに書く") {
            val store = InMemoryObjectStore()
            val published = FilePublisher(store, workDir).publish(Fixtures.request(FileFormat.PARQUET), Fixtures.rows(25)).ok()

            published.dataKey shouldBe "inbox/sales_daily_20261014160000_001.parquet"
            published.manifestKey shouldBe "inbox/sales_daily_20261014160000_001.manifest.json"
            store.keys() shouldContainExactly listOf(published.manifestKey, published.dataKey)
            val data = store.objects.getValue(published.dataKey)
            val manifest = FileManifest.parse(store.objects.getValue(published.manifestKey)).ok()
            manifest shouldBe published.manifest
            manifest.recordCount shouldBe 25
            manifest.sizeBytes shouldBe data.size.toLong()
            manifest.sha256 shouldBe DataFiles.sha256(data.inputStream())
            manifest.schemaVersion shouldBe "sales_daily.v1"
            manifest.traceparent shouldBe Fixtures.TRACEPARENT
            // マニフェストは最後(データファイルの後)に置く
            store.mutations shouldBe 2

            val file = Files.createTempFile(workDir, "read", ".parquet").also { Files.write(it, data) }
            val footer = ParquetFiles.readFooter(file).shouldBeInstanceOf<FooterResult.Read>().footer
            footer.rowCount shouldBe 25
            ParquetFiles.mismatches(Fixtures.LAYOUT, footer).shouldBeEmpty()
            // 一時ファイルは残さない
            Files.list(workDir).use { files -> files.filter { it.fileName.toString().startsWith("eiaf-file-") }.count() } shouldBe 0
        }

        test("CSV + gzip: RFC 4180(CRLF・ヘッダ)。区切り・囲み・改行を含む値と空の文字列は囲み、null は空で書く") {
            val store = InMemoryObjectStore()
            val rows =
                sequenceOf(
                    Fixtures.row(1, region = "a,b"),
                    Fixtures.row(2, region = "say \"hi\""),
                    Fixtures.row(3, region = "line1\r\nline2"),
                    Fixtures.row(4, region = ""),
                    Fixtures.row(5, region = null),
                )
            val published = FilePublisher(store, workDir).publish(Fixtures.request(FileFormat.CSV_GZIP), rows).ok()

            published.dataKey shouldBe "inbox/sales_daily_20261014160000_001.csv.gz"
            val text = GZIPInputStream(store.objects.getValue(published.dataKey).inputStream()).readAllBytes().decodeToString()
            val lines = text.split("\r\n")
            lines[0] shouldBe "business_date,order_id,ordered_at,status,total_minor_units,lines,gift,ship_region"
            lines[1] shouldBe "2026-10-14,order-0001,2026-10-14T01:02:04.123456Z,PLACED,1000,2,false,\"a,b\""
            lines[2] shouldContain ",\"say \"\"hi\"\"\""
            lines[3] shouldContain ",\"line1"
            lines[4] shouldBe "line2\""
            lines[5] shouldContain ",\"\""
            lines[6] shouldBe "2026-10-14,order-0005,2026-10-14T01:02:08.123456Z,CANCELLED,5000,3,false,"
            lines.last() shouldBe ""
            published.manifest.recordCount shouldBe 5
        }

        test("0 件でも、ヘッダだけのファイル(Parquet は行のないファイル)とマニフェストを置く") {
            val store = InMemoryObjectStore()
            FilePublisher(store, workDir)
                .publish(Fixtures.request(FileFormat.PARQUET), emptySequence())
                .ok()
                .manifest.recordCount shouldBe
                0
            FilePublisher(store, workDir)
                .publish(Fixtures.request(FileFormat.CSV_GZIP), emptySequence())
                .ok()
                .manifest.recordCount shouldBe 0
        }

        test("行が契約に合わない(型・null・列の数・取りうる値)なら、何も置かずに使い方の誤り。理由に値は入れない") {
            val store = InMemoryObjectStore()
            val publisher = FilePublisher(store, workDir)
            val secret = "secret-value"
            val cases =
                listOf(
                    Fixtures.row(1).toMutableList().also { it[1] = 42 } to "order_id の値は String",
                    Fixtures.row(1).toMutableList().also { it[0] = null } to "business_date は null にできません",
                    Fixtures.row(1).dropLast(1) to "列の数",
                    Fixtures.row(1).toMutableList().also { it[3] = secret } to "status の値が取りうる値にありません",
                )
            for (format in FileFormat.entries) {
                for ((bad, expected) in cases) {
                    val error =
                        publisher
                            .publish(Fixtures.request(format), Fixtures.rows(3) + sequenceOf(bad))
                            .err()
                            .shouldBeInstanceOf<FileTransferMisuse>()
                    error.message shouldContain "4 行目"
                    error.message shouldContain expected
                    error.message.contains(secret) shouldBe false
                }
            }
            store.keys().shouldBeEmpty()
        }

        test("traceparent の形式が誤っていれば、データファイルを書く前に使い方の誤り") {
            val store = InMemoryObjectStore()
            FilePublisher(store, workDir)
                .publish(Fixtures.request(FileFormat.PARQUET, traceparent = "nope"), Fixtures.rows(1))
                .err()
                .shouldBeInstanceOf<FileTransferMisuse>()
            store.keys().shouldBeEmpty()
        }

        test("置き場が使えなければその失敗を返す。データファイルを置いた後に落ちても、マニフェストがなければ受け側からは見えない") {
            val store = InMemoryObjectStore().apply { crashAt = 2 }
            val publisher = FilePublisher(store, workDir)
            publisher.publish(Fixtures.request(FileFormat.PARQUET), Fixtures.rows(2)).err() shouldBe StoreUnavailable("crash")
            store.keys() shouldContainExactly listOf("inbox/sales_daily_20261014160000_001.parquet")

            // 同じ名前で呼び直せば、上書きしてマニフェストまで置く
            store.crashAt = null
            publisher.publish(Fixtures.request(FileFormat.PARQUET), Fixtures.rows(2)).ok()
            store.keys().size shouldBe 2
        }
    })
