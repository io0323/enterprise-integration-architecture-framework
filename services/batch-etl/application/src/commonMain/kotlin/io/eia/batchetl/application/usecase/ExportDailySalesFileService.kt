package io.eia.batchetl.application.usecase

import io.eia.batchetl.application.port.inbound.ExportDailySalesFileUseCase
import io.eia.batchetl.application.port.inbound.ExportedSalesFile
import io.eia.batchetl.application.port.inbound.SalesFileReport
import io.eia.batchetl.application.port.outbound.DailySalesLines
import io.eia.batchetl.application.port.outbound.SalesFileFormat
import io.eia.batchetl.application.port.outbound.SalesFileStore
import io.eia.batchetl.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok

/**
 * 日次の売上ファイルを、[formats] の順に書き出す。形式ごとに mart を読み直す(行をメモリに溜めないため)。
 * 途中の形式で失敗したら、そこで止めて失敗を返す(書いた形式は、やり直しで同じ名前に上書きされる)。
 */
public class ExportDailySalesFileService(
    private val lines: DailySalesLines,
    private val store: SalesFileStore,
    private val formats: List<SalesFileFormat>,
) : ExportDailySalesFileUseCase {
    init {
        require(formats.isNotEmpty() && formats.distinct() == formats) { "形式は 1 つ以上、重複なしにしてください" }
    }

    override suspend fun invoke(
        day: BusinessDay,
        revision: Int,
    ): Result<SalesFileReport, DomainError> {
        val files = mutableListOf<ExportedSalesFile>()
        for (format in formats) {
            when (val exported = lines.read(day) { store.publish(day, revision, format, it) }) {
                is Result.Err -> return exported
                is Result.Ok -> files += ExportedSalesFile(format, exported.value.name, exported.value.records)
            }
        }
        return ok(SalesFileReport(files))
    }
}
