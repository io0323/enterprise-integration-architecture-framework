package io.eia.shared.kernel

// Result の変換・合成の演算子。

public inline fun <T, E, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> =
    fold(onOk = { Result.Ok(transform(it)) }, onErr = { Result.Err(it) })

public inline fun <T, E, F> Result<T, E>.mapError(transform: (E) -> F): Result<T, F> =
    fold(onOk = { Result.Ok(it) }, onErr = { Result.Err(transform(it)) })

public inline fun <T, E, R> Result<T, E>.flatMap(transform: (T) -> Result<R, E>): Result<R, E> = fold(transform) { Result.Err(it) }

/** 失敗を別の成功値または失敗に置き換える。 */
public inline fun <T, E, F> Result<T, E>.recover(transform: (E) -> Result<T, F>): Result<T, F> = fold({ Result.Ok(it) }, transform)

public inline fun <T, E> Result<T, E>.getOrElse(default: (E) -> T): T = fold({ it }, default)

public inline fun <T, E> Result<T, E>.onOk(action: (T) -> Unit): Result<T, E> = also { if (it is Result.Ok) action(it.value) }

public inline fun <T, E> Result<T, E>.onErr(action: (E) -> Unit): Result<T, E> = also { if (it is Result.Err) action(it.error) }

/** 2 つの成功値を合成する。どちらかが失敗なら最初の失敗を返す。 */
public inline fun <A, B, E, R> Result<A, E>.zip(
    other: Result<B, E>,
    transform: (A, B) -> R,
): Result<R, E> = flatMap { a -> other.map { b -> transform(a, b) } }

/** すべて成功なら成功値のリストを、1 つでも失敗があれば最初の失敗を返す。 */
public fun <T, E> Iterable<Result<T, E>>.combine(): Result<List<T>, E> {
    val values = mutableListOf<T>()
    for (result in this) {
        when (result) {
            is Result.Ok -> values += result.value
            is Result.Err -> return result
        }
    }
    return Result.Ok(values)
}
