package io.eia.good.app

import io.eia.good.adapters.OrderRoutes
import io.eia.good.application.PlaceOrderUseCase

fun wire(routes: OrderRoutes): PlaceOrderUseCase = routes.useCase
