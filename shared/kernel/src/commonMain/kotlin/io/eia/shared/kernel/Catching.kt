package io.eia.shared.kernel

import kotlin.coroutines.cancellation.CancellationException

/**
 * [block] の例外を [UnexpectedError] に変換する。`runCatching` の代わりに境界(adapters)で使う(ADR-0004 §3)。
 *
 * コルーチンのキャンセルを壊さないよう [CancellationException] は捕捉せずに再送出する。`Error`(OOM など)も捕捉しない。
 */
public inline fun <T> catching(block: () -> T): Result<T, UnexpectedError> = catching(UnexpectedError::from, block)

/** [block] の例外を [classify] で [DomainError] に分類する。Retryable / NonRetryable の判定は [classify] が行う。 */
@Suppress("TooGenericExceptionCaught") // 境界で全例外を DomainError に変換するのがこの関数の責務
public inline fun <T, E : DomainError> catching(
    classify: (Exception) -> E,
    block: () -> T,
): Result<T, E> =
    try {
        Result.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Err(classify(e))
    }
