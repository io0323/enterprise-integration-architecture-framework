package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.GetOrdersUseCase
import io.eia.order.application.port.inbound.OrderBatch
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.domain.OrderId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.map

/** [GetOrdersUseCase] の実装。 */
public class GetOrdersService(
    private val repository: OrderRepository,
) : GetOrdersUseCase {
    override suspend fun invoke(ids: List<String>): Result<OrderBatch, DomainError> {
        val unique = ids.distinct()
        val reason =
            when {
                unique.isEmpty() -> "1 件以上にしてください"
                unique.size > GetOrdersUseCase.MAX_IDS -> "${GetOrdersUseCase.MAX_IDS} 件以下にしてください"
                else -> null
            }
        if (reason != null) return err(ValidationError.of("ids", reason))
        val parsed = unique.associateWith { OrderId.parse(it).getOrNull() }
        return repository.findByIds(parsed.values.filterNotNull().toSet()).map { found ->
            val byId = found.associateBy { it.id.value }
            OrderBatch(
                orders = unique.mapNotNull { byId[it] },
                notFound = unique.filterNot { it in byId },
            )
        }
    }
}
