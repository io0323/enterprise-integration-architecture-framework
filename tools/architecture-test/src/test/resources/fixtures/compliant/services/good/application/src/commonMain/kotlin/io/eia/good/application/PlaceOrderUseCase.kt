package io.eia.good.application

import io.eia.good.domain.OrderId
import io.eia.shared.kernel.Result

class PlaceOrderUseCase {
    operator fun invoke(id: OrderId): Result<OrderId, String> = id.validate()
}
