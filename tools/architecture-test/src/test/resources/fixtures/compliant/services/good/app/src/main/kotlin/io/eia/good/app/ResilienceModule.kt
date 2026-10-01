package io.eia.good.app

import io.eia.platform.reliability.ResilienceMetrics
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig

// 準拠: ResilienceMetrics 経由で作る。型としての Resilience の参照や、コメントの中の Resilience(...) は違反にしない
fun inventory(
    metrics: ResilienceMetrics,
    config: ResilienceConfig,
): Resilience = metrics.resilience("inventory", config)
