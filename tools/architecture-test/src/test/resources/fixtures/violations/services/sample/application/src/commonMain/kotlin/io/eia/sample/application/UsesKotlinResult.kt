package io.eia.sample.application

// 違反: kotlin.Result の import と戻り値型、runCatching
import kotlin.Result

class UsesKotlinResult {
    fun parse(text: String): Result<Int> = runCatching { text.toInt() }
}
