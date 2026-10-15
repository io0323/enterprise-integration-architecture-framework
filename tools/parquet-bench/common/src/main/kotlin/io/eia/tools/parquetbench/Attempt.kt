package io.eia.tools.parquetbench

/**
 * 失敗も計測の結果として残すための「値か、失敗の説明」(例: ネイティブが読み込めない、Hadoop のクラスがない)。
 * kotlin.Result と runCatching はリポジトリの規約で使わない(ADR-0004)。計測のコードは shared/kernel に依存しないので、ここに小さく持つ。
 */
sealed interface Attempt<out T> {
    data class Ok<T>(
        val value: T,
    ) : Attempt<T>

    data class Failed(
        val error: String,
    ) : Attempt<Nothing>

    fun valueOrNull(): T? = (this as? Ok<T>)?.value

    fun errorOrNull(): String? = (this as? Failed)?.error
}

inline fun <T> attempt(block: () -> T): Attempt<T> =
    try {
        Attempt.Ok(block())
    } catch (expected: Throwable) {
        // NoClassDefFoundError(Error)も結果として残す
        Attempt.Failed("${expected::class.java.name}: ${expected.message}")
    }
