package io.eia.platform.reliability

import io.eia.shared.kernel.DomainError
import io.eia.shared.resilience.AttemptTimedOut
import io.eia.shared.resilience.CircuitState
import io.eia.shared.resilience.DeadlineExceeded
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.ResilienceError
import io.eia.shared.resilience.ResilienceListener
import io.eia.shared.resilience.ResilienceRejection
import io.eia.shared.resilience.RetrySuppression
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * `shared/resilience` の出来事を OTel のメトリクスに写す(ADR-0021 §7)。サービスで 1 つを作り、すべての [Resilience] で共有する。
 *
 * | メトリクス | 種類 | 属性(依存先の名前 `eia.dependency.name` のほか) |
 * |---|---|---|
 * | `eia.resilience.circuit_breaker.state` | gauge | `state`(`closed` / `open` / `half_open`)。現在の状態だけ 1、ほかは 0 |
 * | `eia.resilience.circuit_breaker.transitions` | counter | `from`・`to` |
 * | `eia.resilience.retries` | counter | なし |
 * | `eia.resilience.retries.suppressed` | counter | `reason`(`deadline` / `circuit_open` / `budget_exhausted`) |
 * | `eia.resilience.retry_budget.tokens` | gauge | なし。リトライバジェットの残高 |
 * | `eia.resilience.rejections` | counter | `kind`(`circuit_open` / `bulkhead_full`) |
 * | `eia.resilience.timeouts` | counter | `kind`(`attempt` / `deadline`) |
 * | `eia.resilience.fallbacks` | counter | なし |
 *
 * 属性は、依存先の名前と、上の決まった値だけにする(カーディナリティ対策)。エラーのメッセージ・URL・ステータスは入れない。
 * 依存先の名前には、依存先ごとに決まった値(`inventory-api` など)を使い、要求ごとに変わる値(ID・URL)を入れない。
 *
 * gauge は [register] した [Resilience] の状態を、収集のたびに読む。Open の期間が過ぎても、次の呼び出しまでは Open を示す
 * (Half-Open への遷移は呼び出しで起きる。ADR-0021 §4)。
 */
public class ResilienceMetrics(
    meter: Meter,
) : ResilienceListener {
    private val registered = ConcurrentHashMap<String, Resilience>()

    private val transitions: LongCounter =
        meter.counter("eia.resilience.circuit_breaker.transitions", "{transition}", "Circuit Breaker の状態の遷移")
    private val retries: LongCounter = meter.counter("eia.resilience.retries", "{retry}", "リトライした回数")
    private val suppressed: LongCounter =
        meter.counter("eia.resilience.retries.suppressed", "{retry}", "RetryPolicy が認めたリトライを見送った回数(理由別)")
    private val rejections: LongCounter =
        meter.counter("eia.resilience.rejections", "{call}", "Circuit Breaker と Bulkhead が依存先に送らずに断った件数")
    private val timeouts: LongCounter =
        meter.counter("eia.resilience.timeouts", "{call}", "試行と、呼び出し全体の締め切りのタイムアウトの件数")
    private val fallbacks: LongCounter = meter.counter("eia.resilience.fallbacks", "{call}", "Fallback を使った件数")

    init {
        meter
            .gaugeBuilder("eia.resilience.circuit_breaker.state")
            .ofLongs()
            .setUnit("1")
            .setDescription("Circuit Breaker の状態(現在の状態だけ 1)")
            .buildWithCallback { measurement ->
                registered.values.forEach { resilience ->
                    val state = resilience.circuitBreaker?.state ?: return@forEach
                    CircuitState.entries.forEach {
                        measurement.record(if (it == state) 1L else 0L, attributes(resilience.name, STATE, it.label()))
                    }
                }
            }
        meter
            .gaugeBuilder("eia.resilience.retry_budget.tokens")
            .setUnit("{token}")
            .setDescription("リトライバジェットの残高。maxTokens の半分以下の間はリトライしない")
            .buildWithCallback { measurement ->
                registered.values.forEach { resilience ->
                    resilience.retryBudgetTokens?.let { measurement.record(it, attributes(resilience.name)) }
                }
            }
    }

    /**
     * [resilience] の状態(Circuit Breaker・リトライバジェット)を gauge に出す。[resilience] はこのインスタンスを listener に渡して作る。
     *
     * 同じ名前を 2 回登録すると例外にする。依存先ごとに 1 つの [Resilience] を使い回す約束(ADR-0021)を破って、
     * 呼び出しごとに作っている誤りを見つけるため(Circuit Breaker とリトライバジェットの状態が呼び出しごとに捨てられてしまう)。
     */
    public fun register(resilience: Resilience) {
        require(registered.putIfAbsent(resilience.name, resilience) == null) {
            "Resilience '${resilience.name}' は登録済みです。依存先ごとに 1 つを作って使い回してください"
        }
    }

    /** このメトリクスを listener にした [Resilience] を作り、[register] する。 */
    public fun resilience(
        name: String,
        config: ResilienceConfig,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
        random: Random = Random.Default,
    ): Resilience = Resilience(name, config, timeSource, random, this).also(::register)

    override fun onRetry(
        name: String,
        attempt: Int,
        delay: Duration,
        error: DomainError,
    ) {
        retries.add(1, attributes(name))
    }

    override fun onRetrySuppressed(
        name: String,
        reason: RetrySuppression,
    ) {
        suppressed.add(1, attributes(name, REASON, reason.name.lowercase()))
    }

    override fun onStateTransition(
        name: String,
        from: CircuitState,
        to: CircuitState,
    ) {
        transitions.add(1, Attributes.of(DEPENDENCY, name, FROM, from.label(), TO, to.label()))
    }

    /** `kind` は拒否のコード(`CircuitOpen` は `circuit_open`、`BulkheadFull` は `bulkhead_full`)。 */
    override fun onRejected(rejection: ResilienceRejection) {
        rejections.add(1, attributes(rejection.name, KIND, rejection.code))
    }

    override fun onTimeout(error: ResilienceError) {
        val kind =
            when (error) {
                is AttemptTimedOut -> "attempt"
                is DeadlineExceeded -> "deadline"
                is ResilienceRejection -> return
            }
        timeouts.add(1, attributes(error.name, KIND, kind))
    }

    override fun onFallback(
        name: String,
        error: DomainError,
    ) {
        fallbacks.add(1, attributes(name))
    }

    public companion object {
        /** 依存先の名前(`Resilience.name`)。 */
        public val DEPENDENCY: AttributeKey<String> = AttributeKey.stringKey("eia.dependency.name")
        private val STATE: AttributeKey<String> = AttributeKey.stringKey("state")
        private val FROM: AttributeKey<String> = AttributeKey.stringKey("from")
        private val TO: AttributeKey<String> = AttributeKey.stringKey("to")
        private val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")
        private val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")

        private fun attributes(name: String): Attributes = Attributes.of(DEPENDENCY, name)

        private fun attributes(
            name: String,
            key: AttributeKey<String>,
            value: String,
        ): Attributes = Attributes.of(DEPENDENCY, name, key, value)

        private fun CircuitState.label(): String = name.lowercase()

        private fun Meter.counter(
            name: String,
            unit: String,
            description: String,
        ): LongCounter =
            counterBuilder(name)
                .setUnit(unit)
                .setDescription(description)
                .build()
    }
}
