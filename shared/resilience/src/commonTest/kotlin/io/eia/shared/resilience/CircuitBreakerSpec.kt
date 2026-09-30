@file:OptIn(ExperimentalCoroutinesApi::class)

package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.isOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val CONFIG =
    CircuitBreakerConfig(
        window = SlidingWindow.Count(10),
        failureRateThreshold = 0.5,
        minimumCalls = 4,
        openDuration = 10.seconds,
        halfOpenPermits = 2,
    )

private fun TestCoroutineScheduler.breaker(
    listener: ResilienceListener,
    config: CircuitBreakerConfig = CONFIG,
) = CircuitBreaker("inventory", config, timeSource, listener)

private suspend fun CircuitBreaker.fail(times: Int) = repeat(times) { execute { failure() } }

private suspend fun CircuitBreaker.succeed(times: Int) = repeat(times) { execute { success() } }

class CircuitBreakerSpec :
    FunSpec({
        coroutineTestScope = true

        context("Closed → Open") {
            test("最小件数に達し、失敗率がしきい値以上になると Open になる") {
                val listener = RecordingListener()
                val breaker = testScheduler.breaker(listener)
                breaker.succeed(2)
                breaker.fail(1)
                breaker.state shouldBe CircuitState.CLOSED
                breaker.fail(1) // 4 件中 2 件の失敗 = 50%
                breaker.state shouldBe CircuitState.OPEN
                listener.transitions shouldContainExactly listOf(CircuitState.CLOSED to CircuitState.OPEN)
            }

            test("最小件数に満たないうちは、全件が失敗でも開かない") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(3)
                breaker.state shouldBe CircuitState.CLOSED
            }

            test("NonRetryable(4xx・業務エラー)は失敗に数えない") {
                val breaker = testScheduler.breaker(RecordingListener())
                repeat(10) { breaker.execute { failure(INVALID) } }
                breaker.state shouldBe CircuitState.CLOSED
                breaker.fail(4) // 直近 10 件のうち 4 件の失敗 = 40%
                breaker.state shouldBe CircuitState.CLOSED
                breaker.fail(1) // 50%
                breaker.state shouldBe CircuitState.OPEN
            }

            test("件数の窓は古い結果を捨てる") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(1)
                breaker.succeed(9) // 窓(10 件)は失敗 1 件 + 成功 9 件
                breaker.fail(4) // 最初の失敗が押し出され、失敗は 4 件(40%)
                breaker.state shouldBe CircuitState.CLOSED
                breaker.fail(1)
                breaker.state shouldBe CircuitState.OPEN
            }

            test("時間の窓は、窓の外に出た失敗を数えない") {
                val config = CONFIG.copy(window = SlidingWindow.Time(10.seconds, buckets = 10))
                val breaker = testScheduler.breaker(RecordingListener(), config)
                breaker.fail(3)
                delay(11.seconds)
                breaker.succeed(3)
                breaker.fail(2) // 窓の中は 5 件中 2 件 = 40%
                breaker.state shouldBe CircuitState.CLOSED
                breaker.fail(1) // 6 件中 3 件 = 50%
                breaker.state shouldBe CircuitState.OPEN
            }

            test("時間の窓は、ちょうど窓の長さだけ経った区間を外す") {
                val config = CONFIG.copy(window = SlidingWindow.Time(10.seconds, buckets = 10))
                val breaker = testScheduler.breaker(RecordingListener(), config)
                breaker.fail(3) // 区間 0
                delay(9.seconds)
                breaker.fail(1) // 区間 9。区間 0 はまだ窓の中(4 件中 4 件)
                breaker.state shouldBe CircuitState.OPEN

                val other = testScheduler.breaker(RecordingListener(), config)
                other.fail(3) // 区間 n
                delay(10.seconds)
                other.fail(1) // 区間 n + 10。区間 n は窓の外(1 件)
                other.state shouldBe CircuitState.CLOSED
            }

            test("例外で終わった呼び出しは数えず、例外はそのまま伝える") {
                val breaker = testScheduler.breaker(RecordingListener())
                repeat(10) { shouldThrow<Boom> { breaker.execute<String> { throw Boom() } } }
                breaker.state shouldBe CircuitState.CLOSED
            }
        }

        context("Open") {
            test("呼び出さずに CircuitOpen を返し、retryAfter は Open が明けるまでの残り時間") {
                val listener = RecordingListener()
                val breaker = testScheduler.breaker(listener)
                breaker.fail(4)
                delay(3.seconds)
                var called = false
                val error =
                    breaker
                        .execute {
                            called = true
                            success()
                        }.errorOrFail()
                called shouldBe false
                error shouldBe CircuitOpen("inventory", 7.seconds)
                listener.events.last() shouldBe "rejected:circuit_open"
            }
        }

        context("Open → Half-Open → Closed / Open") {
            test("openDuration の後の呼び出しで Half-Open になり、試した全件が成功すると Closed に戻る") {
                val listener = RecordingListener()
                val breaker = testScheduler.breaker(listener)
                breaker.fail(4)
                delay(10.seconds)
                breaker.state shouldBe CircuitState.OPEN // 時間だけでは遷移しない
                breaker.succeed(1)
                breaker.state shouldBe CircuitState.HALF_OPEN
                breaker.succeed(1)
                breaker.state shouldBe CircuitState.CLOSED
                listener.transitions shouldContainExactly
                    listOf(
                        CircuitState.CLOSED to CircuitState.OPEN,
                        CircuitState.OPEN to CircuitState.HALF_OPEN,
                        CircuitState.HALF_OPEN to CircuitState.CLOSED,
                    )
            }

            test("Half-Open で 1 件でも失敗すると Open に戻り、Open の期間を測り直す") {
                val listener = RecordingListener()
                val breaker = testScheduler.breaker(listener)
                breaker.fail(4)
                delay(10.seconds)
                breaker.succeed(1)
                breaker.fail(1)
                breaker.state shouldBe CircuitState.OPEN
                breaker.execute { success() }.errorOrFail() shouldBe CircuitOpen("inventory", 10.seconds)
                listener.transitions shouldContainExactly
                    listOf(
                        CircuitState.CLOSED to CircuitState.OPEN,
                        CircuitState.OPEN to CircuitState.HALF_OPEN,
                        CircuitState.HALF_OPEN to CircuitState.OPEN,
                    )
            }

            test("Closed に戻った後は、窓を空にして数え直す") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(4)
                delay(10.seconds)
                breaker.succeed(2)
                breaker.fail(3)
                breaker.state shouldBe CircuitState.CLOSED
            }

            test("Half-Open では NonRetryable も成功に数える(依存先は応答している)") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(4)
                delay(10.seconds)
                repeat(2) { breaker.execute { failure(INVALID) } }
                breaker.state shouldBe CircuitState.CLOSED
            }

            test("遷移を知らせるリスナーが例外を投げても、Half-Open の枠は返す") {
                val throwing =
                    object : ResilienceListener {
                        var armed = false

                        override fun onStateTransition(
                            name: String,
                            from: CircuitState,
                            to: CircuitState,
                        ) {
                            if (armed && to == CircuitState.HALF_OPEN) throw Boom()
                        }
                    }
                val breaker = testScheduler.breaker(throwing)
                breaker.fail(4)
                delay(10.seconds)
                throwing.armed = true
                shouldThrow<Boom> { breaker.execute { success() } }
                throwing.armed = false
                breaker.succeed(2)
                breaker.state shouldBe CircuitState.CLOSED
            }

            test("Half-Open で例外に終わった試行は、枠を返して別の呼び出しに試させる") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(4)
                delay(10.seconds)
                shouldThrow<Boom> { breaker.execute<String> { throw Boom() } }
                breaker.state shouldBe CircuitState.HALF_OPEN
                breaker.succeed(2)
                breaker.state shouldBe CircuitState.CLOSED
            }
        }

        context("並行呼び出し") {
            test("Half-Open で同時に通すのは halfOpenPermits 件だけで、残りは CircuitOpen(retryAfter なし)") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(4)
                delay(10.seconds)
                val gate = CompletableDeferred<Unit>()
                var entered = 0
                val results =
                    coroutineScope {
                        val calls =
                            List(5) {
                                async {
                                    breaker.execute {
                                        entered++
                                        gate.await()
                                        success()
                                    }
                                }
                            }
                        testScheduler.runCurrent()
                        entered shouldBe 2
                        gate.complete(Unit)
                        calls.awaitAll()
                    }
                results.count { it.isOk } shouldBe 2
                results.filterIsInstance<Result.Err<DomainError>>().map { it.error }.toSet() shouldBe
                    setOf(CircuitOpen("inventory", retryAfter = null))
                breaker.state shouldBe CircuitState.CLOSED
            }

            test("同時に失敗しても、状態は壊れず Open への遷移は 1 回だけ") {
                val listener = RecordingListener()
                val breaker = testScheduler.breaker(listener, CONFIG.copy(window = SlidingWindow.Count(100), minimumCalls = 50))
                coroutineScope {
                    List(200) {
                        launch {
                            breaker.execute {
                                delay(1.milliseconds)
                                failure()
                            }
                        }
                    }
                }
                breaker.state shouldBe CircuitState.OPEN
                listener.transitions shouldContainExactly listOf(CircuitState.CLOSED to CircuitState.OPEN)
            }

            test("Open の前に始まり、Half-Open の後に返った呼び出しは、Half-Open の判定に数えない") {
                val listener = RecordingListener()
                val breaker = testScheduler.breaker(listener)
                val slowGate = CompletableDeferred<Unit>()
                coroutineScope {
                    val slow =
                        async {
                            breaker.execute {
                                slowGate.await()
                                failure()
                            }
                        }
                    testScheduler.runCurrent()
                    breaker.fail(4) // Closed → Open(遅い呼び出しは Closed の世代のまま)
                    delay(10.seconds)
                    breaker.succeed(1) // Open → Half-Open
                    slowGate.complete(Unit)
                    slow.await()
                }
                breaker.state shouldBe CircuitState.HALF_OPEN // 遅れて返った失敗で Open に戻らない
                breaker.succeed(1)
                breaker.state shouldBe CircuitState.CLOSED
            }

            test("Half-Open の試行がキャンセルされたら、枠を返す") {
                val breaker = testScheduler.breaker(RecordingListener())
                breaker.fail(4)
                delay(10.seconds)
                coroutineScope {
                    val job =
                        launch {
                            breaker.execute {
                                delay(1.seconds)
                                success()
                            }
                        }
                    testScheduler.runCurrent()
                    job.cancel()
                }
                breaker.succeed(2)
                breaker.state shouldBe CircuitState.CLOSED
            }
        }

        context("設定") {
            test("不正な値は拒否する") {
                shouldThrow<IllegalArgumentException> { CircuitBreakerConfig(failureRateThreshold = 0.0) }
                shouldThrow<IllegalArgumentException> { CircuitBreakerConfig(failureRateThreshold = 1.1) }
                shouldThrow<IllegalArgumentException> { CircuitBreakerConfig(halfOpenPermits = 0) }
                shouldThrow<IllegalArgumentException> { CircuitBreakerConfig(openDuration = 0.seconds) }
                shouldThrow<IllegalArgumentException> {
                    CircuitBreakerConfig(window = SlidingWindow.Count(5), minimumCalls = 6)
                }
                shouldThrow<IllegalArgumentException> { SlidingWindow.Time(1.seconds, buckets = 0) }
                shouldThrow<IllegalArgumentException> { SlidingWindow.Time(Duration.INFINITE) }
                CircuitBreakerConfig().window.shouldBeInstanceOf<SlidingWindow.Count>()
            }
        }
    })
