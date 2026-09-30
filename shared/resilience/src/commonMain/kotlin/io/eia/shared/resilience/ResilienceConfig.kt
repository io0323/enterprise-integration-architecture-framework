package io.eia.shared.resilience

import io.eia.shared.kernel.RetryPolicy
import kotlin.time.Duration

/**
 * 1 つの依存先の回復性の設定(ADR-0021)。同期呼び出しの 4 点セット(Framework 13.3)のうち、
 * Timeout は必須、Retry と Circuit Breaker は既定で有効にする。Fallback は業務ごとに呼び出し側が渡す([Fallback])。
 *
 * @property attemptTimeout 1 回の試行の上限。必須(Framework 13.1: 全呼出しに明示設定)
 * @property deadline リトライを含む呼び出し全体の締め切り(タイムバジェット)。`null` は締め切りなし。
 *   呼び出しごとに [Resilience.execute] の引数で上書きできる。呼び出し元の締め切り([CallDeadline])の残り時間の方が
 *   短ければ、そちらを使う(ADR-0021 §12)
 * @property retry 待ち時間と回数の規則。kernel の [RetryPolicy] をそのまま使う。`null` はリトライしない
 * @property retryBudget リトライの割合の上限。`null` は上限なし
 * @property circuitBreaker `null` は Circuit Breaker なし
 * @property bulkhead `null` は同時実行数の上限なし(既定。多くの依存先を持つサービスで設定する)
 */
public data class ResilienceConfig(
    public val attemptTimeout: Duration,
    public val deadline: Duration? = null,
    public val retry: RetryPolicy? = RetryPolicy.DEFAULT,
    public val retryBudget: RetryBudgetConfig? = RetryBudgetConfig(),
    public val circuitBreaker: CircuitBreakerConfig? = CircuitBreakerConfig(),
    public val bulkhead: BulkheadConfig? = null,
) {
    init {
        require(attemptTimeout.isPositive()) { "attemptTimeout は正の値です: $attemptTimeout" }
        require(deadline == null || deadline.isPositive()) { "deadline は正の値です: $deadline" }
    }
}
