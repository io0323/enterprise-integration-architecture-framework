package io.eia.order.adapters.inbound.rest

import io.eia.order.application.port.inbound.GetOrderUseCase
import io.eia.order.application.port.inbound.PlaceOrderUseCase
import io.eia.platform.api.idempotency.IdempotencyHandler
import io.eia.platform.api.idempotency.TransactionBoundary
import io.eia.shared.kernel.money.CurrencyResolver

/** 注文の API の部品。app が Koin で組み立てて [orderRoutes] に渡す。 */
public class OrderApi(
    public val placeOrder: PlaceOrderUseCase,
    public val getOrder: GetOrderUseCase,
    public val idempotency: IdempotencyHandler,
    public val transaction: TransactionBoundary,
    public val currencies: CurrencyResolver = CurrencyResolver.COMMON,
)
