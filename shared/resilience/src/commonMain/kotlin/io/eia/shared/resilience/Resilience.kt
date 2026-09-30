package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryDecision
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.err
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * - 締め切りまでの残り時間を超えて待つリトライはしない。試行の Timeout も残り時間までに縮める。
 *   Circuit Breaker が開いたら、待たずに返す。
 * - 呼び出し元の締め切り([CallDeadline])があれば、自分の締め切りとの短い方を使う。[block] には試行の残り時間を
 *   [CallDeadline] で渡すので、[block] の中の [Resilience](入れ子)もそれを超えて待たない(ADR-0021 §12)。
 *   呼び出し元の締め切りで `attemptTimeout` より前に打ち切った試行は、Circuit Breaker にもリトライバジェットにも数えない。
 * - 手元で断った呼び出し([ResilienceRejection])はリトライしない。
 * - タイムアウトは coroutines のタイムアウトで行い、呼び出し側のキャンセル(`CancellationException`)は捕まえずに伝える。
 * - [block] は例外ではなく `Result` で失敗を返す(境界の例外は `catching` で変換しておく)。[block] の例外はそのまま伝える。
 * - 期限の直前に [block] が成功しても、期限切れとして扱われてリトライされることがある。
 *   リトライする呼び出しは冪等にする(POST は `Idempotency-Key` 必須。Framework 5.5・13.1)。冪等でなければ `retry = null` を渡す。
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
     * リトライバジェットの残高(トークン数。設定がなければ `null`)。メトリクスの gauge に出すために公開する(ADR-0021 §7)。
     * ロックを取らずに読む最新の値で、読んだ直後に変わりうる。
     */
    public val retryBudgetTokens: Double? get() = retryBudget?.remaining

    /**
     * [block] を呼ぶ。
     *
     * @param deadline この呼び出しの締め切り(タイムバジェット)。既定は [ResilienceConfig.deadline]。
     *   呼び出し元の締め切り([CallDeadline])の残り時間の方が短ければ、そちらを使う(延ばすことはできない)。
     *   実際の締め切りが 0 以下なら [block] を呼ばずに [DeadlineExceeded] を返す
     * @param retry この呼び出しの RetryPolicy。既定は [ResilienceConfig.retry]。冪等でない呼び出しは `null` を渡して
     *   リトライを止める(Circuit Breaker とリトライバジェットは、同じ依存先の状態を共有したまま使う)
     */
    public suspend fun <T> execute(
        deadline: Duration? = config.deadline,
        retry: RetryPolicy? = config.retry,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> = retrying(effectiveDeadline(deadline), retry, block)

    /** [execute] の最終的なエラーが [fallback] の対象なら、代替動作の結果を返す。 */
    public suspend fun <T> execute(
        fallback: Fallback<T>,
        deadline: Duration? = config.deadline,
        retry: RetryPolicy? = config.retry,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        val result = execute(deadline, retry, block)
        if (result !is Result.Err || !fallback.appliesTo(result.error)) return result
        listener.onFallback(name, result.error)
        return fallback.handler(result.error)
    }

    /**
     * 自分の締め切り [own] と、呼び出し元の締め切り([CallDeadline])の残り時間の短い方。どちらもなければ `null`。
     * 同じなら自分の締め切りとする(数える側に倒す)。呼び出し元の期限を過ぎていれば 0 にする([DeadlineExceeded] の予算に負の値を入れない)。
     */
    private suspend fun effectiveDeadline(own: Duration?): Deadline? {
        val inherited = CallDeadline.current()?.remaining()?.coerceAtLeast(Duration.ZERO)
        val now = timeSource.markNow()
        return when {
            inherited != null && (own == null || inherited < own) -> Deadline(inherited, now + inherited, DeadlineSource.CALLER)
            own != null -> Deadline(own, now + own, DeadlineSource.OWN)
            else -> null
        }
    }

    @Suppress("ReturnCount") // リトライを見送る理由ごとに、その時点の結果を返す
    private suspend fun <T> retrying(
        deadline: Deadline?,
        policy: RetryPolicy?,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        var attempt = 1
        while (true) {
            val result = attemptOnce(deadline, block)
            val error = (result as? Result.Err)?.error ?: return result
            if (policy == null || error is ResilienceRejection || error is DeadlineExceeded) return result
            val decision = policy.decide(attempt, error, random) as? RetryDecision.Retry ?: return result
            val suppression =
                when {
                    deadline != null && decision.delay >= deadline.remaining() -> RetrySuppression.DEADLINE
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

    /**
     * 1 回の試行。締め切りがあれば、試行の Timeout と Bulkhead の待ち時間を残り時間までに縮める(ADR-0021 §1)。
     * 締め切りで打ち切った試行も、依存先が期限内に応答しなかった失敗として Circuit Breaker に数える
     * (数えないと、ハングした依存先に対して Circuit Breaker が開かない)。
     * ただし、呼び出し元の締め切り([DeadlineSource.CALLER])で打ち切った試行は数えない([Outcome.of]。ADR-0021 §12)。
     * 残り時間が `attemptTimeout` 以上なら、`attemptTimeout` に達して打ち切った試行([AttemptTimedOut])として数える。
     *
     * [block] には、この試行の Timeout を [CallDeadline] として渡す(入れ子の [Resilience] に引き継ぐ)。[block] が、その
     * [CallDeadline] で打ち切られた内側の [DeadlineExceeded] を返した場合は、この試行が自分の Timeout で打ち切られたものとして扱う
     * (外側と内側のタイムアウトは同じ時刻に来るため、どちらが先に返っても同じ結果にする)。
     */
    private suspend fun <T> attemptOnce(
        deadline: Deadline?,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        val remaining = deadline?.remaining()
        if (deadline != null && remaining != null && !remaining.isPositive()) return timedOut(deadline.exceeded(name))
        // 締め切りの残り時間が attemptTimeout より短いときだけ、締め切りで打ち切る
        val cutBy = deadline?.takeIf { remaining != null && remaining < config.attemptTimeout }
        val timeout = if (cutBy != null && remaining != null) remaining else config.attemptTimeout
        val onTimeout: ResilienceError = cutBy?.exceeded(name) ?: AttemptTimedOut(name, config.attemptTimeout)
        val timed: suspend () -> Result<T, DomainError> = {
            val attemptDeadline = CallDeadline.after(timeout, timeSource)
            val result = withTimeoutOrNull(timeout) { withContext(attemptDeadline) { block() } }
            if (result == null || result.isCutBy(attemptDeadline)) timedOut(onTimeout) else result
        }
        val isolated: suspend () -> Result<T, DomainError> = { bulkhead?.execute(remaining, timed) ?: timed() }
        val result = circuitBreaker?.execute(isolated) ?: isolated()
        retryBudget?.record(Outcome.of(result))
        return result
    }

    /**
     * タイムアウトを知らせて Err にする。タイムアウトは `withTimeoutOrNull` で行う。`withTimeoutOrNull` は自分の期限切れだけを
     * `null` にし、呼び出し側のキャンセルや [block] の中の別のタイムアウトは、捕まえずにそのまま伝える。
     */
    private fun timedOut(error: ResilienceError): Result<Nothing, DomainError> {
        listener.onTimeout(error)
        return err(error)
    }

    /**
     * 締め切り。[budget] はこの呼び出しの予算(自分の締め切りと呼び出し元の残り時間の短い方)、[at] は期限の時刻、
     * [source] は予算を決めたもの。
     */
    private class Deadline(
        val budget: Duration,
        private val at: ComparableTimeMark,
        private val source: DeadlineSource,
    ) {
        fun remaining(): Duration = -at.elapsedNow()

        fun exceeded(name: String): DeadlineExceeded = DeadlineExceeded(name, budget, source)
    }

    /** 内側の [Resilience] が、この試行の [attemptDeadline] で打ち切られた結果か(期限を過ぎていて、内側が呼び出し元の締め切りで打ち切った)。 */
    private fun Result<*, DomainError>.isCutBy(attemptDeadline: CallDeadline): Boolean {
        val error = (this as? Result.Err)?.error
        return error is DeadlineExceeded && error.source == DeadlineSource.CALLER && !attemptDeadline.remaining().isPositive()
    }
}
