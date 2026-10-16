package io.eia.batchetl.application

import io.eia.batchetl.application.port.inbound.ExportedSalesFile
import io.eia.batchetl.application.port.outbound.DailySalesLines
import io.eia.batchetl.application.port.outbound.ExportedFileInfo
import io.eia.batchetl.application.port.outbound.SalesFileFormat
import io.eia.batchetl.application.port.outbound.SalesFileStore
import io.eia.batchetl.application.usecase.ExportDailySalesFileService
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.OrderStatus
import io.eia.batchetl.domain.SalesFileLine
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val DAY = BusinessDay.parse("2026-10-14").ok()

private fun line(id: String) =
    SalesFileLine(DAY.date, id, Instant.parse("2026-10-14T01:00:00Z"), OrderStatus.PLACED, 1_000, "JPY", "JP", "JP-13")

/** mart の代わり。読まれた回数を数える。 */
private class FakeLines(
    private val lines: List<SalesFileLine>,
) : DailySalesLines {
    var reads = 0

    override suspend fun <T> read(
        day: BusinessDay,
        consume: suspend (Sequence<SalesFileLine>) -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        reads++
        return consume(lines.asSequence())
    }
}

/** 置き場の代わり。[failOn] の形式で失敗する。 */
private class FakeStore(
    private val failOn: SalesFileFormat? = null,
) : SalesFileStore {
    val published = mutableListOf<Triple<SalesFileFormat, Int, List<String>>>()

    override suspend fun publish(
        day: BusinessDay,
        revision: Int,
        format: SalesFileFormat,
        lines: Sequence<SalesFileLine>,
    ): Result<ExportedFileInfo, DomainError> {
        if (format == failOn) return err(UnavailableError("置き場を使えません"))
        val ids = lines.map { it.orderId }.toList()
        published += Triple(format, revision, ids)
        return ok(ExportedFileInfo("sales_daily_$revision.$format", ids.size.toLong()))
    }
}

class ExportDailySalesFileSpec :
    FunSpec({
        test("決めた形式ごとに、mart を読み直して置く。版はそのまま渡す") {
            val lines = FakeLines(listOf(line("a"), line("b")))
            val store = FakeStore()
            val service = ExportDailySalesFileService(lines, store, listOf(SalesFileFormat.PARQUET, SalesFileFormat.CSV_GZIP))

            val report = service(DAY, revision = 2).ok()

            report.files shouldBe
                listOf(
                    ExportedSalesFile(SalesFileFormat.PARQUET, "sales_daily_2.PARQUET", 2),
                    ExportedSalesFile(SalesFileFormat.CSV_GZIP, "sales_daily_2.CSV_GZIP", 2),
                )
            lines.reads shouldBe 2
            store.published.map { it.second }.toSet() shouldBe setOf(2)
            store.published.first().third shouldBe listOf("a", "b")
        }

        test("注文のない日も、0 件のファイルを置く(契約の recordCount)") {
            val store = FakeStore()
            ExportDailySalesFileService(FakeLines(emptyList()), store, listOf(SalesFileFormat.PARQUET))(DAY, 1)
                .ok()
                .files
                .single()
                .records shouldBe 0
        }

        test("途中の形式で失敗したら、そこで止めて失敗を返す") {
            val store = FakeStore(failOn = SalesFileFormat.PARQUET)
            val result =
                ExportDailySalesFileService(
                    FakeLines(listOf(line("a"))),
                    store,
                    listOf(SalesFileFormat.PARQUET, SalesFileFormat.CSV_GZIP),
                )(DAY, 1)
            (result is Result.Err) shouldBe true
            store.published shouldBe emptyList()
        }

        test("形式は 1 つ以上、重複なし") {
            shouldThrow<IllegalArgumentException> { ExportDailySalesFileService(FakeLines(emptyList()), FakeStore(), emptyList()) }
            shouldThrow<IllegalArgumentException> {
                ExportDailySalesFileService(FakeLines(emptyList()), FakeStore(), listOf(SalesFileFormat.PARQUET, SalesFileFormat.PARQUET))
            }
        }
    })
