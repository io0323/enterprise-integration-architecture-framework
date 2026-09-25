package io.eia.good.adapters

import io.eia.good.application.PlaceOrderUseCase
import io.eia.good.domain.OrderId
import io.eia.platform.observability.Tracer
import java.time.Instant

class OrderRoutes(
    val useCase: PlaceOrderUseCase,
    val tracer: Tracer,
) {
    fun handle(raw: String): Pair<OrderId, Instant> = OrderId(raw) to Instant.now()
}
