package io.eia.shared.resilience

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * リトライバジェットの設定(ADR-0021 §8)。gRPC の retry throttling(gRFC A6)と同じ方式のトークンバケット。
 *
 * - 残高は [maxTokens] から始まる。Retryable な失敗のたびに 1 減らし、成功のたびに [tokenRatio] 増やす([maxTokens] が上限)。
 * - 残高が [maxTokens] の半分以下の間は、リトライしない(初回の試行はいつも行う)。
 * - [tokenRatio] は小数 3 桁までの精度で扱う(gRFC A6 と同じ。0.001 未満は 0.001 に切り上げ、それより細かい端数は切り捨てる)。
 *
 * 失敗が続くと、リトライの割合がおよそ [tokenRatio] まで下がる。Circuit Breaker のしきい値を下回る失敗率が長く続くときに、
 * リトライで依存先の負荷を増やし続けること(Retry Storm)を防ぐ。
 */
public data class RetryBudgetConfig(
    public val maxTokens: Int = DEFAULT_MAX_TOKENS,
    public val tokenRatio: Double = DEFAULT_TOKEN_RATIO,
) {
    init {
        require(maxTokens >= 1) { "maxTokens は 1 以上です: $maxTokens" }
        require(tokenRatio > 0.0 && tokenRatio <= 1.0) { "tokenRatio は 0 より大きく 1 以下です: $tokenRatio" }
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 10
        const val DEFAULT_TOKEN_RATIO = 0.1
    }
}

/** [RetryBudgetConfig] のトークンバケット。依存先ごとに 1 つを共有する。小数の誤差を避けるため、1/1000 トークン単位で持つ。 */
internal class RetryBudget(
    config: RetryBudgetConfig,
) {
    private val max = config.maxTokens * MILLI
    private val ratio = (config.tokenRatio * MILLI).toInt().coerceAtLeast(1)
    private val mutex = Mutex()

    @Volatile
    private var tokens = max

    /** 残高(トークン数)。ロックを取らずに読む最新の値で、メトリクスの gauge に使う。 */
    val remaining: Double get() = tokens.toDouble() / MILLI

    suspend fun record(outcome: Outcome) {
        mutex.withLock {
            tokens =
                when (outcome) {
                    Outcome.SUCCESS -> minOf(max, tokens + ratio)
                    Outcome.FAILURE -> maxOf(0, tokens - MILLI)
                    Outcome.IGNORED -> tokens
                }
        }
    }

    suspend fun allowsRetry(): Boolean = mutex.withLock { tokens > max / 2 }

    private companion object {
        const val MILLI = 1000
    }
}
