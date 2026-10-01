package io.eia.sample.adapters

// 違反: 完全修飾名でも直接作らない
fun payment(config: io.eia.shared.resilience.ResilienceConfig) = io.eia.shared.resilience.Resilience("payment", config)
