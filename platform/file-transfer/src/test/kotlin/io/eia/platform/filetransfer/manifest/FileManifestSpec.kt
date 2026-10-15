package io.eia.platform.filetransfer.manifest

import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class FileManifestSpec :
    FunSpec({
        val sha = "0".repeat(64)
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

        @Suppress("LongParameterList") // 項目ごとに誤りの値に差し替える
        fun json(
            file: String = "sales_daily_20260925010000_001.csv.gz",
            recordCount: Long = 10,
            sha256: String = sha,
            schemaVersion: String = "sales_daily.v1",
            createdAt: String = "2026-09-25T01:00:00Z",
            trace: String = traceparent,
            correlationId: String = "corr-1",
            sizeBytes: String = "",
        ) = """
            {"file":"$file","recordCount":$recordCount,"sha256":"$sha256","schemaVersion":"$schemaVersion","createdAt":"$createdAt",
             "traceparent":"$trace","correlationId":"$correlationId"$sizeBytes,"contentType":"text/csv","future":{"x":1}}
            """.trimIndent().toByteArray()

        test("契約(manifest.v1)の形のマニフェストを読む。未知の項目は無視する") {
            val manifest = FileManifest.parse(json(sizeBytes = ""","sizeBytes":120""")).ok()

            manifest.file shouldBe "sales_daily_20260925010000_001.csv.gz"
            manifest.recordCount shouldBe 10
            manifest.createdAt shouldBe Instant.parse("2026-09-25T01:00:00Z")
            manifest.traceparent shouldBe traceparent
            manifest.sizeBytes shouldBe 120
            FileManifest.parse(json()).ok().sizeBytes shouldBe null
        }

        test("toJson は契約の形で書き、parse で同じ値に読み直せる。sizeBytes がなければ項目を出さない") {
            val manifest = FileManifest.parse(json(sizeBytes = ""","sizeBytes":120""")).ok()

            FileManifest.parse(manifest.toJson()).ok() shouldBe manifest
            val withoutSize = manifest.copy(sizeBytes = null)
            withoutSize.toJson().decodeToString().contains("sizeBytes") shouldBe false
            FileManifest.parse(withoutSize.toJson()).ok() shouldBe withoutSize
        }

        test("項目の形式の誤りは、項目の名前ごとに返す(値は入れない)") {
            val error =
                FileManifest
                    .parse(
                        json(
                            file = "../etc/passwd",
                            recordCount = -1,
                            sha256 = "XYZ",
                            schemaVersion = "v1",
                            createdAt = "2026-09-25T10:00:00+09:00",
                            trace = "nope",
                            correlationId = "",
                            sizeBytes = ""","sizeBytes":-5""",
                        ),
                    ).err()

            error.violations.map { it.field } shouldContainExactlyInAnyOrder
                listOf("file", "recordCount", "sha256", "schemaVersion", "createdAt", "traceparent", "correlationId", "sizeBytes")
            error.message.contains("passwd") shouldBe false
        }

        test("JSON でない・必須の項目がない・大きすぎる・日時が読めないマニフェストは読まない") {
            FileManifest
                .parse("not json".toByteArray())
                .err()
                .violations
                .single()
                .field shouldBe "manifest"
            FileManifest
                .parse("{\"file\":\"a\"}".toByteArray())
                .err()
                .violations
                .single()
                .field shouldBe "manifest"
            FileManifest
                .parse(ByteArray(FileManifest.MAX_BYTES + 1))
                .err()
                .violations
                .single()
                .field shouldBe "manifest"
            FileManifest
                .parse(json(createdAt = "yesterdayZ"))
                .err()
                .violations
                .single()
                .field shouldBe "createdAt"
        }
    })
