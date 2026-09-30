package io.eia.shared.resilience

import io.eia.shared.kernel.isOk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * 実際のスレッドで並列に呼び、状態が壊れないことを確かめる(commonTest の仮想時間はシングルスレッドで、競合を再現しない)。
 * 待ち時間は使わず、時間は [TestTimeSource] で進める。
 */
class ConcurrencyStressSpec :
    FunSpec({
        val calls = 2_000

        test("Closed で並列に失敗しても、Open への遷移は 1 回だけ") {
            val listener = SynchronizedListener()
            val config = CircuitBreakerConfig(window = SlidingWindow.Count(100), minimumCalls = 50, openDuration = 1.minutes)
            val breaker = CircuitBreaker("inventory", config, TestTimeSource(), listener)
            withContext(Dispatchers.Default) {
                List(calls) {
                    launch {
                        breaker.execute {
                            yield()
                            failure()
                        }
                    }
                }
            }
            breaker.state shouldBe CircuitState.OPEN
            listener.transitions() shouldContainExactly listOf(CircuitState.CLOSED to CircuitState.OPEN)
        }

        test("Half-Open で並列に呼んでも、通すのは halfOpenPermits 件だけで、残りは断る") {
            val time = TestTimeSource()
            val config =
                CircuitBreakerConfig(window = SlidingWindow.Count(4), minimumCalls = 4, openDuration = 1.minutes, halfOpenPermits = 5)
            val breaker = CircuitBreaker("inventory", config, time, SynchronizedListener())
            repeat(4) { breaker.execute { failure() } }
            time += 1.minutes
            val entered = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val results =
                withContext(Dispatchers.Default) {
                    val all =
                        List(calls) {
                            async {
                                breaker.execute {
                                    entered.incrementAndGet()
                                    gate.await()
                                    success()
                                }
                            }
                        }
                    // 枠の外の呼び出しがすべて断られるまで、枠の中の呼び出しを止めておく
                    while (all.count { it.isCompleted } < calls - config.halfOpenPermits) yield()
                    gate.complete(Unit)
                    all.awaitAll()
                }
            entered.get() shouldBe config.halfOpenPermits
            results.count { it.isOk } shouldBe config.halfOpenPermits
            results.filterNot { it.isOk }.map { it.errorOrFail() }.toSet() shouldBe setOf(CircuitOpen("inventory", retryAfter = null))
            breaker.state shouldBe CircuitState.CLOSED
        }

        test("Bulkhead は並列に呼んでも上限を守り、待ちのタイムアウトと競合しても許可を漏らさない") {
            val bulkhead = Bulkhead("inventory", BulkheadConfig(maxConcurrentCalls = 4, maxWait = 1.milliseconds))
            val running = AtomicInteger()
            val maxRunning = AtomicInteger()
            withContext(Dispatchers.Default) {
                List(calls) {
                    launch {
                        bulkhead.execute {
                            maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            Thread.onSpinWait()
                            running.decrementAndGet()
                            success()
                        }
                    }
                }
            }
            maxRunning.get() shouldBeLessThanOrEqual 4
            // 許可が 1 つでも漏れていれば、4 件を同時に保持できない
            val held = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            withContext(Dispatchers.Default) {
                val holders =
                    List(4) {
                        async {
                            bulkhead.execute {
                                held.incrementAndGet()
                                gate.await()
                                success()
                            }
                        }
                    }
                while (held.get() < 4 && holders.none { it.isCompleted }) yield()
                gate.complete(Unit)
                holders.awaitAll().all { it.isOk } shouldBe true
            }
        }
    })

/** 複数のスレッドから呼ばれるリスナー。 */
private class SynchronizedListener : ResilienceListener {
    private val recorded = mutableListOf<Pair<CircuitState, CircuitState>>()

    override fun onStateTransition(
        name: String,
        from: CircuitState,
        to: CircuitState,
    ) {
        synchronized(recorded) { recorded += from to to }
    }

    fun transitions(): List<Pair<CircuitState, CircuitState>> = synchronized(recorded) { recorded.toList() }
}
