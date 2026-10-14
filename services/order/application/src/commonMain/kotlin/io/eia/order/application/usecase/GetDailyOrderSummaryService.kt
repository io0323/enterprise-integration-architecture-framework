package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.DailyOrderSummary
import io.eia.order.application.port.inbound.GetDailyOrderSummaryUseCase
import io.eia.order.application.port.outbound.OrderSummaries
import io.eia.order.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map

/** [GetDailyOrderSummaryUseCase] の実装。区間は業務の 1 日(日本時間。ADR-0031 §1)。 */
public class GetDailyOrderSummaryService(
    private val summaries: OrderSummaries,
) : GetDailyOrderSummaryUseCase {
    override suspend fun invoke(day: BusinessDay): Result<DailyOrderSummary, DomainError> =
        summaries.count(day.start, day.end).map { DailyOrderSummary(day, day.start, day.end, it.orders, it.digest) }
}
