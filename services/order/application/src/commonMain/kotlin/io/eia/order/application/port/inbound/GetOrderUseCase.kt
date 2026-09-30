package io.eia.order.application.port.inbound

import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 注文を取得する(`GET /v1/orders/{orderId}`)。ないときは `NotFoundError`。 */
public interface GetOrderUseCase {
    public suspend operator fun invoke(id: OrderId): Result<Order, DomainError>
}
