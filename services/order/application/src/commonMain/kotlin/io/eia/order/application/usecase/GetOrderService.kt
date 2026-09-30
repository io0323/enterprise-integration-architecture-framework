package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.GetOrderUseCase
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok

/** [GetOrderUseCase] の実装。 */
public class GetOrderService(
    private val repository: OrderRepository,
) : GetOrderUseCase {
    override suspend fun invoke(id: OrderId): Result<Order, DomainError> =
        repository.findById(id).flatMap { order -> order?.let { ok(it) } ?: err(NotFoundError("order", id.value)) }
}
