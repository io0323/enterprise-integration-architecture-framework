package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import kotlin.time.Duration

/**
 * 回復性の部品の出来事を外へ知らせる(ADR-0021 §7)。`platform/reliability` が OTel のメトリクスに写す。
 *
 * 呼び出しの経路で同期的に呼ばれるので、重い処理や例外を投げる処理をしない。
 * Circuit Breaker の状態の遷移は、内部のロックを外してから知らせる。
 */
public interface ResilienceListener {
    /** [attempt] 回目(1 始まり)の試行が [error] で失敗し、[delay] 待ってリトライする。 */
    public fun onRetry(
        name: String,
        attempt: Int,
        delay: Duration,
        error: DomainError,
    ) {}

    /** RetryPolicy はリトライを認めたが、[reason] でリトライしなかった。 */
    public fun onRetrySuppressed(
        name: String,
        reason: RetrySuppression,
    ) {}

    /** Circuit Breaker の状態が [from] から [to] に変わった。 */
    public fun onStateTransition(
        name: String,
        from: CircuitState,
        to: CircuitState,
    ) {}

    /** Circuit Breaker か Bulkhead が、依存先に送らずに断った。 */
    public fun onRejected(rejection: ResilienceRejection) {}

    /** 1 回の試行か、呼び出し全体がタイムアウトした。 */
    public fun onTimeout(error: ResilienceError) {}

    /** 最終的なエラー [error] に対して Fallback を使った。 */
    public fun onFallback(
        name: String,
        error: DomainError,
    ) {}

    public companion object {
        /** 何もしない。 */
        public val NONE: ResilienceListener = object : ResilienceListener {}
    }
}

/** RetryPolicy が認めたリトライを見送った理由。 */
public enum class RetrySuppression {
    /** 待ち時間が締め切りまでの残り時間を超える。 */
    DEADLINE,

    /** Circuit Breaker が開いた。次の試行はどうせ断られるので、待たずに返す。 */
    CIRCUIT_OPEN,

    /** リトライバジェットを使い切った(Retry Storm の防止)。 */
    BUDGET_EXHAUSTED,
}
