package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Bulkhead の設定(ADR-0021 §5)。
 *
 * @property maxConcurrentCalls 同時に実行できる呼び出しの上限
 * @property maxWait 空きを待つ時間の上限。既定の 0 は待たない(上限に達していればすぐ [BulkheadFull] を返す)
 */
public data class BulkheadConfig(
    public val maxConcurrentCalls: Int,
    public val maxWait: Duration = Duration.ZERO,
) {
    init {
        require(maxConcurrentCalls >= 1) { "maxConcurrentCalls は 1 以上です: $maxConcurrentCalls" }
        require(!maxWait.isNegative()) { "maxWait は 0 以上です: $maxWait" }
    }
}

/**
 * Bulkhead(Framework 13.1)。依存先ごとに同時実行数を分け、1 つの依存先の遅延で呼び出し側の資源を使い切らないようにする。
 * 依存先ごとに 1 つのインスタンスを共有する。
 */
public class Bulkhead(
    public val name: String,
    private val config: BulkheadConfig,
    private val listener: ResilienceListener = ResilienceListener.NONE,
) {
    private val semaphore = Semaphore(config.maxConcurrentCalls)

    /** [block] を呼ぶ。上限に達していて [BulkheadConfig.maxWait] の間に空かなければ、呼ばずに [BulkheadFull] を返す。 */
    public suspend fun <T> execute(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        if (!acquire()) {
            val rejection = BulkheadFull(name)
            listener.onRejected(rejection)
            return err(rejection)
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    private suspend fun acquire(): Boolean =
        semaphore.tryAcquire() ||
            (config.maxWait.isPositive() && withTimeoutOrNull(config.maxWait) { semaphore.acquire() } != null)
}
