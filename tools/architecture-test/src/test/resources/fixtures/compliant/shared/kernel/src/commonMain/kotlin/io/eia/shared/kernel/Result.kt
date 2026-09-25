package io.eia.shared.kernel

sealed interface Result<out T, out E> {
    data class Ok<T>(
        val value: T,
    ) : Result<T, Nothing>

    data class Err<E>(
        val error: E,
    ) : Result<Nothing, E>
}

// 同一パッケージでは import なしの Result<...> は kernel の Result に解決される
fun <T> ok(value: T): Result<T, Nothing> = Result.Ok(value)
