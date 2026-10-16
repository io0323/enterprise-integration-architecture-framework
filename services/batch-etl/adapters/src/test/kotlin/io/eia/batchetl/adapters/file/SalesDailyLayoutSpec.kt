package io.eia.batchetl.adapters.file

import io.eia.batchetl.domain.OrderStatus
import io.eia.batchetl.domain.SalesFileLine
import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import kotlin.time.Instant

/** 売上ファイルの形([SalesDailyLayout])が、契約(`contracts/files/sales_daily.v1.yaml`)と一致すること(ADR-0032 §2)。 */
class SalesDailyLayoutSpec :
    FunSpec({
        val contract: JsonNode = YAMLMapper().readTree(File(checkNotNull(System.getProperty("eia.salesFileContract"))))
        val layout = SalesDailyLayout.V1

        test("system・dataset・version(manifest の schemaVersion)") {
            contract.get("system").stringValue() shouldBe layout.system
            contract.get("dataset").stringValue() shouldBe layout.dataset
            contract.get("version").asInt() shouldBe layout.version
            layout.schemaVersion shouldBe "sales_daily.v1"
        }

        test("列の名前・順序・型・nullable・取りうる値") {
            val types =
                mapOf(
                    "string" to ColumnType.STRING,
                    "int32" to ColumnType.INT32,
                    "int64" to ColumnType.INT64,
                    "boolean" to ColumnType.BOOLEAN,
                    "date" to ColumnType.DATE,
                    "timestamp" to ColumnType.TIMESTAMP,
                )
            val columns = contract.get("columns").values().toList()
            columns.map { it.get("name").stringValue() } shouldBe layout.columns.map { it.name }
            columns.zip(layout.columns).forEach { (expected, actual) ->
                types[expected.get("type").stringValue()] shouldBe actual.type
                expected.get("nullable").asBoolean() shouldBe actual.nullable
                expected
                    .get("enum")
                    ?.values()
                    ?.map { it.stringValue() }
                    ?.toSet() shouldBe actual.enumValues
            }
        }

        test("形式: Parquet と CSV + gzip の拡張子、CSV の区切り・囲み・ヘッダ・改行・null の書き方") {
            val formats = contract.get("formats").values().associateBy { it.get("type").stringValue() }
            formats.getValue("parquet").get("extension").stringValue() shouldBe FileFormat.PARQUET.extension
            formats.getValue("parquet").get("compression").stringValue() shouldBe "zstd"
            val csv = formats.getValue("csv")
            csv.get("extension").stringValue() shouldBe FileFormat.CSV_GZIP.extension
            csv.get("compression").stringValue() shouldBe "gzip"
            csv.get("delimiter").stringValue() shouldBe layout.csv.delimiter.toString()
            csv.get("quote").stringValue() shouldBe layout.csv.quote.toString()
            csv.get("header").asBoolean() shouldBe layout.csv.header
            csv.get("lineSeparator").stringValue() shouldBe layout.csv.lineSeparator
            csv.get("nullValue").stringValue() shouldBe layout.csv.nullValue
        }

        test("1 行を列の順の値にする(届け先の都道府県がなければ null)") {
            val line =
                SalesFileLine(
                    LocalDate(2026, 10, 14),
                    "order-1",
                    Instant.parse("2026-10-14T01:02:03.123456Z"),
                    OrderStatus.SHIPPED,
                    1_234,
                    "JPY",
                    "JP",
                    null,
                )
            SalesDailyLayout.row(line) shouldBe
                listOf(
                    java.time.LocalDate.of(2026, 10, 14),
                    "order-1",
                    java.time.Instant.parse("2026-10-14T01:02:03.123456Z"),
                    "SHIPPED",
                    1_234L,
                    "JPY",
                    "JP",
                    null,
                )
        }
    })
