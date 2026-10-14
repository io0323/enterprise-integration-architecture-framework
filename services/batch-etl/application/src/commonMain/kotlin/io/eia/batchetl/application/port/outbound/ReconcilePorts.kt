package io.eia.batchetl.application.port.outbound

import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DailySummary
import io.eia.batchetl.domain.DayReconciliation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** order-service の日次の集計(`GET /sales/v1/order-summaries/daily/{date}`。INT-SALES-001)。 */
public interface OrderSummarySource {
    public suspend fun summary(day: BusinessDay): Result<DailySummary, DomainError>
}

/** 分析の日次のパーティションの集計。 */
public interface MartSummaries {
    /** [day] のパーティションの集計。パーティションをまだ作っていなければ null。 */
    public suspend fun summary(day: BusinessDay): Result<DailySummary?, DomainError>
}

/** 照合の結果の記録(運用の確認・メトリクス)。 */
public interface ReconciliationLog {
    public suspend fun record(
        runDay: BusinessDay,
        results: List<DayReconciliation>,
    ): Result<Unit, DomainError>
}
