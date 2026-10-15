package io.eia.platform.filetransfer.layout

import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.Fixtures
import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

class FileLayoutSpec :
    FunSpec({
        test("スキーマの版は {system}_{dataset}.v{version}") {
            Fixtures.LAYOUT.schemaVersion shouldBe "sales_daily.v1"
            Fixtures.LAYOUT.toString() shouldBe "FileLayout(sales_daily.v1)"
        }

        test("名前の形式・版・列の重複・列がないことを拒否する") {
            val column = FileColumn("a", ColumnType.STRING, nullable = false)
            val error =
                FileLayout
                    .of("Sales", "daily_x", 0, listOf(column, column, FileColumn("B", ColumnType.INT32, false)))
                    .err()
                    .shouldBeInstanceOf<FileTransferMisuse>()
            error.message shouldContain "system の形式"
            error.message shouldContain "dataset の形式"
            error.message shouldContain "version は 1 以上"
            error.message shouldContain "列の名前の形式(B)"
            error.message shouldContain "列の重複(a)"
            FileLayout.of("sales", "daily", 1, emptyList()).err().message shouldContain "列がありません"
        }

        test("ファイルの名前は {system}_{dataset}_{UTC の yyyyMMddHHmmss}_{seq 3 桁以上}.{ext}") {
            val name = FileName.of(Fixtures.LAYOUT, Instant.parse("2026-10-14T16:05:09Z"), 7, FileFormat.CSV_GZIP).ok()
            name.dataFile shouldBe "sales_daily_20261014160509_007.csv.gz"
            name.manifestFile shouldBe "sales_daily_20261014160509_007.manifest.json"
            FileName.of(Fixtures.LAYOUT, Fixtures.CREATED_AT, 1234, FileFormat.PARQUET).ok().base shouldBe "sales_daily_20261014160000_1234"
            FileName.of(Fixtures.LAYOUT, Fixtures.CREATED_AT, 0, FileFormat.PARQUET).err().shouldBeInstanceOf<FileTransferMisuse>()
        }

        test("受け側は拡張子で形式を選ぶ") {
            FileFormat.ofFileName("x.parquet") shouldBe FileFormat.PARQUET
            FileFormat.ofFileName("x.csv.gz") shouldBe FileFormat.CSV_GZIP
            FileFormat.ofFileName("x.csv") shouldBe null
        }
    })
