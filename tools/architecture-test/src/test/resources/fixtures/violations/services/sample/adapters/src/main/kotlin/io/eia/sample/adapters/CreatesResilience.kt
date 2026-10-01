package io.eia.sample.adapters

import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import kotlin.time.Duration.Companion.seconds

// 違反: Resilience(...) を直接作っている(ResilienceMetrics.resilience(...) で作る)
val inventory = Resilience("inventory", ResilienceConfig(attemptTimeout = 1.seconds))
