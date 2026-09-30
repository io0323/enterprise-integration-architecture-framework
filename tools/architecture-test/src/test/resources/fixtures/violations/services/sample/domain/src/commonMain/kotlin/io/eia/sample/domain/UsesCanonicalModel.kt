package io.eia.sample.domain

import io.eia.shared.canonical.sales.Order

// 違反: domain が Canonical Model を import している(ADR-0010 Decision 7)
class OrderSnapshot(
    val order: Order,
    val status: io.eia.shared.canonical.sales.OrderStatus,
)
