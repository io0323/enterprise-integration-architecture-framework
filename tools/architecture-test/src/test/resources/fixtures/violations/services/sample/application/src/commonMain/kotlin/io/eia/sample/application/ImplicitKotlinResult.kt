package io.eia.sample.application

// 違反: import なしの Result<...> は kotlin.Result に解決される。完全修飾名での使用も禁止
class ImplicitKotlinResult {
    fun load(): Result<String> = Result.success("ok")

    val cached: kotlin.Result<String>? = null
}
