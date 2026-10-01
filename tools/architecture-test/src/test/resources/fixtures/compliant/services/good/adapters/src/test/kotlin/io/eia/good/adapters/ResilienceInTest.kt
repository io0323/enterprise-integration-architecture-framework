package io.eia.good.adapters

import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import kotlin.time.Duration.Companion.seconds

// 準拠: テストのソースセットでは直接作ってよい
val fake = Resilience("fake", ResilienceConfig(attemptTimeout = 1.seconds))
