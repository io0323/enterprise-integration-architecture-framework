package io.eia.good.adapters

import io.eia.good.domain.OrderId
import io.eia.shared.canonical.sales.OrderId as CanonicalOrderId

// adapters は Canonical Model と domain の間を変換してよい(ADR-0010 Decision 7)
fun OrderId.toCanonical(): CanonicalOrderId = CanonicalOrderId(value)
