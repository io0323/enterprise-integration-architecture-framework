package io.eia.shared.resilience

// 準拠: kotlin と kernel だけに依存する
import io.eia.shared.kernel.Result
import kotlin.random.Random

class TraceParent(val random: Random, val parsed: Result<String, String>)
