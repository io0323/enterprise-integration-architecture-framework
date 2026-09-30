package io.eia.platform.reliability

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.BulkheadConfig
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.Fallback
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.RetryBudgetConfig
import io.eia.shared.resilience.SlidingWindow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val FAILURE: Result<String, DomainError> = err(UnavailableError("down"))
private val FIXED = RetryPolicy(initialDelay = 100.milliseconds, maxAttempts = 3, jitter = Jitter.NONE)

/** 収集した 1 つのメトリクスの、属性(文字列にしたもの)ごとの値。 */
private fun Collection<MetricData>.points(name: String): Map<Map<String, String>, Number> {
    val metric = singleOrNull { it.name == name } ?: return emptyMap()
    val longs = metric.longSumData.points + metric.longGaugeData.points
    val doubles = metric.doubleGaugeData.points
    return longs.associate { it.attributes.asStrings() to it.value } + doubles.associate { it.attributes.asStrings() to it.value }
}

private fun Attributes.asStrings(): Map<String, String> = asMap().entries.associate { (k, v) -> k.key to v.toString() }

private fun dependency(
    name: String,
    vararg extra: Pair<String, String>,
): Map<String, String> = mapOf("eia.dependency.name" to name) + extra

class ResilienceMetricsSpec :
    FunSpec({
        coroutineTestScope = true

        lateinit var reader: InMemoryMetricReader
        lateinit var metrics: ResilienceMetrics

        beforeTest {
            reader = InMemoryMetricReader.create()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
            metrics = ResilienceMetrics(provider.get("test"))
        }

        test("Circuit Breaker の状態(gauge)と遷移、拒否") {
            val config =
                ResilienceConfig(
                    attemptTimeout = 1.seconds,
                    retry = null,
                    retryBudget = null,
                    circuitBreaker =
                        CircuitBreakerConfig(
                            window = SlidingWindow.Count(2),
                            minimumCalls = 2,
                            openDuration = 10.seconds,
                            halfOpenPermits = 1,
                        ),
                )
            val resilience = metrics.resilience("inventory", config, testScheduler.timeSource)
            reader.collectAllMetrics().points("eia.resilience.circuit_breaker.state") shouldBe
                mapOf(
                    dependency("inventory", "state" to "closed") to 1L,
                    dependency("inventory", "state" to "open") to 0L,
                    dependency("inventory", "state" to "half_open") to 0L,
                )

            repeat(2) { resilience.execute { FAILURE } }
            resilience.execute { ok("never") }
            val opened = reader.collectAllMetrics()
            opened.points("eia.resilience.circuit_breaker.state")[dependency("inventory", "state" to "open")] shouldBe 1L
            opened.points("eia.resilience.rejections") shouldBe mapOf(dependency("inventory", "kind" to "circuit_open") to 1L)

            delay(10.seconds)
            resilience.execute { ok("ok") }
            val closed = reader.collectAllMetrics()
            closed.points("eia.resilience.circuit_breaker.state")[dependency("inventory", "state" to "closed")] shouldBe 1L
            closed.points("eia.resilience.circuit_breaker.transitions") shouldBe
                mapOf(
                    dependency("inventory", "from" to "closed", "to" to "open") to 1L,
                    dependency("inventory", "from" to "open", "to" to "half_open") to 1L,
                    dependency("inventory", "from" to "half_open", "to" to "closed") to 1L,
                )
        }

        test("リトライの回数・見送り・リトライバジェットの残り") {
            val config =
                ResilienceConfig(
                    attemptTimeout = 1.seconds,
                    retry = FIXED,
                    retryBudget = RetryBudgetConfig(maxTokens = 4, tokenRatio = 0.1),
                    circuitBreaker = null,
                )
            val resilience = metrics.resilience("payment", config, testScheduler.timeSource)
            reader.collectAllMetrics().points("eia.resilience.retry_budget.tokens") shouldBe mapOf(dependency("payment") to 4.0)

            // 失敗 2 回で残高 4 → 2(半分)になり、2 回目のリトライを見送る
            resilience.execute { FAILURE }
            val collected = reader.collectAllMetrics()
            collected.points("eia.resilience.retries") shouldBe mapOf(dependency("payment") to 1L)
            collected.points("eia.resilience.retries.suppressed") shouldBe
                mapOf(dependency("payment", "reason" to "budget_exhausted") to 1L)
            collected.points("eia.resilience.retry_budget.tokens") shouldBe mapOf(dependency("payment") to 2.0)
        }

        test("締め切りによるリトライの見送り、試行と締め切りのタイムアウト、Fallback") {
            val config = ResilienceConfig(attemptTimeout = 1.seconds, deadline = 1500.milliseconds, retry = FIXED, circuitBreaker = null)
            val resilience = metrics.resilience("shipping", config, testScheduler.timeSource)

            // 1 回目は attemptTimeout、2 回目は残り時間(400ms)で打ち切られる
            resilience.execute(Fallback.whenUnavailable { ok("cached") }) {
                delay(5.seconds)
                ok("late")
            } shouldBe ok("cached")
            resilience.execute(deadline = 50.milliseconds) { FAILURE }

            val collected = reader.collectAllMetrics()
            collected.points("eia.resilience.timeouts") shouldBe
                mapOf(dependency("shipping", "kind" to "attempt") to 1L, dependency("shipping", "kind" to "deadline") to 1L)
            collected.points("eia.resilience.retries.suppressed") shouldBe mapOf(dependency("shipping", "reason" to "deadline") to 1L)
            collected.points("eia.resilience.fallbacks") shouldBe mapOf(dependency("shipping") to 1L)
        }

        test("Bulkhead の拒否") {
            val config =
                ResilienceConfig(
                    attemptTimeout = 1.seconds,
                    retry = null,
                    circuitBreaker = null,
                    bulkhead = BulkheadConfig(maxConcurrentCalls = 1),
                )
            val resilience = metrics.resilience("catalog", config, testScheduler.timeSource)
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val holder =
                    async {
                        resilience.execute {
                            release.await()
                            ok("held")
                        }
                    }
                yield()
                resilience.execute { ok("never") }
                release.complete(Unit)
                holder.await()
            }

            reader.collectAllMetrics().points("eia.resilience.rejections") shouldBe
                mapOf(dependency("catalog", "kind" to "bulkhead_full") to 1L)
        }

        test("属性は依存先の名前と決まった値だけ(エラーのメッセージを入れない)") {
            val config =
                ResilienceConfig(attemptTimeout = 1.seconds, retry = FIXED, circuitBreaker = CircuitBreakerConfig(minimumCalls = 1))
            val resilience = metrics.resilience("orders", config, testScheduler.timeSource)
            resilience.execute { err(UnavailableError("customer 123 at http://orders.test/v1/orders/9")) }

            val points = reader.collectAllMetrics().flatMap { it.data.points }
            val keys =
                points.flatMap {
                    it.attributes
                        .asMap()
                        .keys
                        .map { key -> key.key }
                }
            val expected = listOf("eia.dependency.name", "state", "from", "to", "reason")
            keys.distinct() shouldContainExactlyInAnyOrder expected
        }

        test("同じ名前の Resilience を 2 回登録すると例外にする(呼び出しごとに作る誤りを見つける)") {
            val config = ResilienceConfig(attemptTimeout = 1.seconds)
            metrics.resilience("inventory", config)
            shouldThrow<IllegalArgumentException> { metrics.resilience("inventory", config) }.message shouldContain "使い回して"
        }
    })
