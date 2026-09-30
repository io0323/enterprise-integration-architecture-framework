package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryDecision
import io.eia.shared.kernel.err
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 1 つの依存先への同期呼び出しを、Timeout・Retry・Circuit Breaker・Bulkhead・Fallback で包む(Framework 13。ADR-0021)。
 * 依存先ごとに 1 つのインスタンスを作って共有する(Circuit Breaker・Bulkhead・リトライバジェットの状態を持つため)。
 *
 * 重ね方は外側から次の順(ADR-0021 §1):
 * ```
 * Fallback → 締め切り(deadline) → Retry → Circuit Breaker → Bulkhead → 1 回の Timeout → block
 * ```
 * - Retry の待ち時間と回数は kernel の [io.eia.shared.kernel.RetryPolicy] で決める(Retry-After の優先を含む)。
 * - 締め切りまでの残り時間を超えて待つリトライはしない。Circuit Breaker が開いたら、待たずに返す。
 * - 手元で断った呼び出し([ResilienceRejection])はリトライしない。
 * - タイムアウトは coroutines のタイムアウトで行い、呼び出し側のキャンセル(`CancellationException`)は捕まえずに伝える。
 * - [block] は例外ではなく `Result` で失敗を返す(境界の例外は `catching` で変換しておく)。[block] の例外はそのまま伝える。
 *
 * @param timeSource 締め切りと Circuit Breaker の Open の期間を測る単調な時刻。テストでは仮想時間を渡す
 * @param random リトライの Jitter に使う乱数。テストでは固定の種を渡す
 */
public class Resilience(
    public val name: String,
    public val config: ResilienceConfig,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val random: Random = Random.Default,
    private val listener: ResilienceListener = ResilienceListener.NONE,
) {
    /** Circuit Breaker(設定がなければ `null`)。状態をメトリクスに出すために公開する。 */
    public val circuitBreaker: CircuitBreaker? = config.circuitBreaker?.let { CircuitBreaker(name, it, timeSource, listener) }

    private val bulkhead: Bulkhead? = config.bulkhead?.let { Bulkhead(name, it, listener) }
    private val retryBudget: RetryBudget? = config.retryBudget?.let(::RetryBudget)

    /**
     * [block] を呼ぶ。
     *
     * @param deadline この呼び出しの締め切り。既定は [ResilienceConfig.deadline]
     */
    public suspend fun <T> execute(
        deadline: Duration? = config.deadline,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        if (deadline == null) return retrying(deadlineAt = null, block)
        require(deadline.isPositive()) { "deadline は正の値です: $deadline" }
        return withTimeoutOrNull(deadline) { retrying(timeSource.markNow() + deadline, block) }
            ?: DeadlineExceeded(name, deadline).let { error ->
                listener.onTimeout(error)
                err(error)
            }
    }

    /** [execute] の最終的なエラーが [fallback] の対象なら、代替動作の結果を返す。 */
    public suspend fun <T> execute(
        fallback: Fallback<T>,
        deadline: Duration? = config.deadline,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        val result = execute(deadline, block)
        if (result !is Result.Err || !fallback.appliesTo(result.error)) return result
        listener.onFallback(name, result.error)
        return fallback.handler(result.error)
    }

    @Suppress("ReturnCount") // リトライを見送る理由ごとに、その時点の結果を返す
    private suspend fun <T> retrying(
        deadlineAt: ComparableTimeMark?,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        val policy = config.retry
        var attempt = 1
        while (true) {
            val result = attemptOnce(block)
            val error = (result as? Result.Err)?.error ?: return result
            if (policy == null || error is ResilienceRejection) return result
            val decision = policy.decide(attempt, error, random) as? RetryDecision.Retry ?: return result
            val suppression =
                when {
                    deadlineAt != null && decision.delay >= -deadlineAt.elapsedNow() -> RetrySuppression.DEADLINE
                    circuitBreaker?.state == CircuitState.OPEN -> RetrySuppression.CIRCUIT_OPEN
                    retryBudget?.allowsRetry() == false -> RetrySuppression.BUDGET_EXHAUSTED
                    else -> null
                }
            if (suppression != null) {
                listener.onRetrySuppressed(name, suppression)
                return result
            }
            listener.onRetry(name, attempt, decision.delay, error)
            delay(decision.delay)
            attempt++
        }
    }

    private suspend fun <T> attemptOnce(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        val timed: suspend () -> Result<T, DomainError> = { withAttemptTimeout(block) }
        val isolated: suspend () -> Result<T, DomainError> = { bulkhead?.execute(timed) ?: timed() }
        val result = circuitBreaker?.execute(isolated) ?: isolated()
        retryBudget?.record(Outcome.of(result))
        return result
    }

    /**
     * 1 回の試行を [ResilienceConfig.attemptTimeout] で打ち切る。`withTimeoutOrNull` は自分の期限切れだけを `null` にし、
     * 呼び出し側のキャンセルや [block] の中の別のタイムアウトはそのまま伝える。
     */
    private suspend fun <T> withAttemptTimeout(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> =
        withTimeoutOrNull(config.attemptTimeout) { block() }
            ?: AttemptTimedOut(name, config.attemptTimeout).let { error ->
                listener.onTimeout(error)
                err(error)
            }
}
