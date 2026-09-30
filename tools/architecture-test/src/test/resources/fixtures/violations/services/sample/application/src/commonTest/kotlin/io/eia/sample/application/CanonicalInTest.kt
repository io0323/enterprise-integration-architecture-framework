package io.eia.sample.application

// 違反: application のテストも Canonical Model を前提にしない(完全修飾名での参照)
val expected = io.eia.shared.canonical.sales.OrderStatus.PLACED
