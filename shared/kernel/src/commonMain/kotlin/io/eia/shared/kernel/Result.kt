package io.eia.shared.kernel

/**
 * 成功値 [T] か失敗 [E] のどちらかを表す。`kotlin.Result` の代わりに全モジュールで使う(ADR-0004 §3)。
 *
 * 業務上の失敗は例外ではなく [Err] で返す。外部ライブラリの例外は境界で [catching] により変換する。
 */
public sealed interface Result<out T, out E> {
    public data class Ok<out T>(
        public val value: T,
    ) : Result<T, Nothing>

    public data class Err<out E>(
        public val error: E,
    ) : Result<Nothing, E>
}

public fun <T> ok(value: T): Result<T, Nothing> = Result.Ok(value)

public fun <E> err(error: E): Result<Nothing, E> = Result.Err(error)

public val Result<*, *>.isOk: Boolean get() = this is Result.Ok

public val Result<*, *>.isErr: Boolean get() = this is Result.Err

public inline fun <T, E, R> Result<T, E>.fold(
    onOk: (T) -> R,
    onErr: (E) -> R,
): R =
    when (this) {
        is Result.Ok -> onOk(value)
        is Result.Err -> onErr(error)
    }

public fun <T> Result<T, *>.getOrNull(): T? = (this as? Result.Ok)?.value

public fun <E> Result<*, E>.errorOrNull(): E? = (this as? Result.Err)?.error
