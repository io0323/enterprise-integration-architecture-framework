package io.eia.batchetl.application.port.inbound

import io.eia.batchetl.application.port.outbound.SalesFileFormat
import io.eia.batchetl.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * [day] の日次の売上ファイルを、決めた形式ごとに書き出して置く(INT-SALES-004。ADR-0031 §8)。
 * 日次のパーティション(mart)ができた後に呼ぶ。同じ [revision] で呼び直すと、同じ名前で上書きする(再実行安全)。
 *
 * @param revision ファイルの版(DAG の実行の世代。作り直すと増える)
 */
public interface ExportDailySalesFileUseCase {
    public suspend operator fun invoke(
        day: BusinessDay,
        revision: Int,
    ): Result<SalesFileReport, DomainError>
}

/** 書き出したファイル(形式ごと)。 */
public data class SalesFileReport(
    val files: List<ExportedSalesFile>,
)

/**
 * @property name データファイルの名前(置き場の接頭辞を除く)
 * @property records データの行数(マニフェストの recordCount)
 */
public data class ExportedSalesFile(
    val format: SalesFileFormat,
    val name: String,
    val records: Long,
)
