@file:OptIn(ExperimentalCoroutinesApi::class)

package io.eia.shared.resilience

import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.RetryPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val FIXED = RetryPolicy(initialDelay = 100.milliseconds, multiplier = 2.0, maxAttempts = 3, jitter = Jitter.NONE)

private val BASE =
    ResilienceConfig(
        attemptTimeout = 5.seconds,
        retry = FIXED,
        retryBudget = null,
        circuitBreaker = null,
    )

private fun TestCoroutineScheduler.resilience(
    name: String = "inventory",
    config: ResilienceConfig = BASE,
    listener: ResilienceListener = ResilienceListener.NONE,
) = Resilience(name, config, timeSource, Random(0), listener)

class CallDeadlineSpec :
    FunSpec({
        // 同じ context の中のテストは仮想時間を共有するので、時刻は各テストの開始(start)からの経過で比べる
        coroutineTestScope = true

        context("withCallDeadline") {
            test("締め切りをコンテキストに置き、残り時間は時間の経過で減る") {
                withCallDeadline(2.seconds, testScheduler.timeSource) {
                    CallDeadline.current()?.remaining() shouldBe 2.seconds
                    delay(500.milliseconds)
                    CallDeadline.current()?.remaining() shouldBe 1_500.milliseconds
                }
                CallDeadline.current() shouldBe null
            }

            test("内側で締め切りを延ばせない(外側の残り時間の方が短ければ、外側を保つ)") {
                withCallDeadline(1.seconds, testScheduler.timeSource) {
                    withCallDeadline(10.seconds, testScheduler.timeSource) {
                        CallDeadline.current()?.remaining() shouldBe 1.seconds
                    }
                }
            }

            test("内側で締め切りを縮めることはできる") {
                withCallDeadline(10.seconds, testScheduler.timeSource) {
                    withCallDeadline(300.milliseconds, testScheduler.timeSource) {
                        CallDeadline.current()?.remaining() shouldBe 300.milliseconds
                    }
                    CallDeadline.current()?.remaining() shouldBe 10.seconds
                }
            }

            test("締め切りを過ぎても block は打ち切らない(知らせるだけ)") {
                val result =
                    withCallDeadline(100.milliseconds, testScheduler.timeSource) {
                        delay(1.seconds)
                        CallDeadline.current()?.remaining()
                    }
                result shouldBe (-900).milliseconds
            }
        }

        context("Resilience は呼び出し元の締め切りを引き継ぐ") {
            test("自分の締め切りがなければ、呼び出し元の残り時間で打ち切る") {
                val start = testScheduler.timeSource.markNow()
                val result =
                    withCallDeadline(500.milliseconds, testScheduler.timeSource) {
                        testScheduler.resilience().execute {
                            delay(2.seconds)
                            success()
                        }
                    }
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", 500.milliseconds, DeadlineSource.CALLER)
                start.elapsedNow() shouldBe 500.milliseconds
            }

            test("自分の締め切りと呼び出し元の残り時間の短い方を使う") {
                val shorterOwn =
                    withCallDeadline(10.seconds, testScheduler.timeSource) {
                        testScheduler.resilience(config = BASE.copy(deadline = 300.milliseconds)).execute {
                            delay(1.hours)
                            success()
                        }
                    }
                shorterOwn.errorOrFail() shouldBe DeadlineExceeded("inventory", 300.milliseconds)

                val shorterCaller =
                    withCallDeadline(300.milliseconds, testScheduler.timeSource) {
                        testScheduler.resilience(config = BASE.copy(deadline = 10.seconds)).execute(deadline = 5.seconds) {
                            delay(1.hours)
                            success()
                        }
                    }
                shorterCaller.errorOrFail() shouldBe DeadlineExceeded("inventory", 300.milliseconds, DeadlineSource.CALLER)
            }

            test("呼び出し元の締め切りを過ぎていたら、呼び出さずに DeadlineExceeded(予算 0)を返し、Circuit Breaker に数えない") {
                val breaker = CircuitBreakerConfig(window = SlidingWindow.Count(1), minimumCalls = 1, halfOpenPermits = 1)
                val resilience = testScheduler.resilience(config = BASE.copy(circuitBreaker = breaker))
                var called = false
                val result =
                    withCallDeadline(100.milliseconds, testScheduler.timeSource) {
                        delay(1.seconds)
                        resilience.execute {
                            called = true
                            success()
                        }
                    }
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", Duration.ZERO, DeadlineSource.CALLER)
                called shouldBe false
                resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
            }

            test("呼び出し元の残り時間を超えて待つリトライはしない") {
                val listener = RecordingListener()
                val config = BASE.copy(retry = FIXED.copy(initialDelay = 2.seconds, maxDelay = 30.seconds))
                val result =
                    withCallDeadline(1.seconds, testScheduler.timeSource) {
                        testScheduler.resilience(config = config, listener = listener).execute { failure() }
                    }
                result.errorOrFail() shouldBe UNAVAILABLE
                listener.events shouldContainExactly listOf("suppressed:DEADLINE")
            }

            test("block には試行の残り時間(試行の Timeout と締め切りの残り時間の短い方)を渡す") {
                val seen = mutableListOf<Duration?>()
                testScheduler.resilience(config = BASE.copy(attemptTimeout = 1.seconds)).execute {
                    seen += CallDeadline.current()?.remaining()
                    success()
                }
                withCallDeadline(300.milliseconds, testScheduler.timeSource) {
                    testScheduler.resilience(config = BASE.copy(attemptTimeout = 1.seconds)).execute {
                        seen += CallDeadline.current()?.remaining()
                        success()
                    }
                }
                seen shouldContainExactly listOf(1.seconds, 300.milliseconds)
            }
        }

        context("入れ子の Resilience") {
            test("内側は外側の試行の残り時間を超えて待たない(待てないリトライはすぐに見送り、外側に返す)") {
                val start = testScheduler.timeSource.markNow()
                val listener = RecordingListener()
                val outer = testScheduler.resilience("downstream", BASE.copy(attemptTimeout = 1.seconds, retry = null))
                val inner =
                    testScheduler.resilience(
                        "oauth-token-endpoint",
                        BASE.copy(deadline = 10.seconds, retry = FIXED.copy(initialDelay = 2.seconds, maxDelay = 30.seconds)),
                        listener,
                    )
                val result = outer.execute { inner.execute { failure() } }
                result.errorOrFail() shouldBe UNAVAILABLE
                listener.events shouldContainExactly listOf("suppressed:DEADLINE")
                start.elapsedNow() shouldBe Duration.ZERO
            }

            test("内側の締め切りは、外側の試行の残り時間になる(内側の設定の締め切りより短ければ)") {
                val outer = testScheduler.resilience("downstream", BASE.copy(attemptTimeout = 5.seconds, deadline = 2.seconds))
                val inner = testScheduler.resilience("oauth-token-endpoint", BASE.copy(deadline = 10.seconds))
                var seen: Duration? = null
                outer.execute {
                    delay(500.milliseconds)
                    inner.execute {
                        seen = CallDeadline.current()?.remaining()
                        success()
                    }
                }
                seen shouldBe 1_500.milliseconds
            }
        }

        context("呼び出し元の予算で打ち切った試行の数え方") {
            val breaker = CircuitBreakerConfig(window = SlidingWindow.Count(10), minimumCalls = 5, halfOpenPermits = 1)

            test("残り時間の短い呼び出しを大量に流しても、Circuit Breaker は開かず、リトライバジェットも減らない") {
                val listener = RecordingListener()
                val config = BASE.copy(attemptTimeout = 1.seconds, retryBudget = RetryBudgetConfig(), circuitBreaker = breaker)
                val resilience = testScheduler.resilience(config = config, listener = listener)
                val tokens = resilience.retryBudgetTokens
                repeat(100) {
                    val result =
                        withCallDeadline(50.milliseconds, testScheduler.timeSource) {
                            resilience.execute {
                                delay(200.milliseconds) // 健全だが、呼び出し元の残り時間より遅い
                                success()
                            }
                        }
                    result.errorOrFail() shouldBe DeadlineExceeded("inventory", 50.milliseconds, DeadlineSource.CALLER)
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
                resilience.retryBudgetTokens shouldBe tokens
                listener.transitions shouldBe emptyList()
            }

            test("attemptTimeout を超える遅延では、呼び出し元の締め切りがあっても開く") {
                val resilience =
                    testScheduler.resilience(
                        config = BASE.copy(attemptTimeout = 1.seconds, retry = null, circuitBreaker = breaker),
                    )
                repeat(5) {
                    val result =
                        withCallDeadline(5.seconds, testScheduler.timeSource) {
                            resilience.execute {
                                delay(1.hours) // ハングした依存先
                                success()
                            }
                        }
                    result.errorOrFail() shouldBe AttemptTimedOut("inventory", 1.seconds)
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }

            test("呼び出し元の残り時間がちょうど attemptTimeout なら、attemptTimeout に達した失敗として数える") {
                val resilience =
                    testScheduler.resilience(
                        config = BASE.copy(attemptTimeout = 1.seconds, retry = null, circuitBreaker = breaker),
                    )
                repeat(5) {
                    withCallDeadline(1.seconds, testScheduler.timeSource) {
                        resilience.execute {
                            delay(1.hours)
                            success()
                        }
                    }.errorOrFail() shouldBe AttemptTimedOut("inventory", 1.seconds)
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }

            test("自分の締め切りで打ち切った試行は、これまでどおり失敗に数える(ADR-0021 §1)") {
                val resilience =
                    testScheduler.resilience(
                        config = BASE.copy(attemptTimeout = 5.seconds, retry = null, circuitBreaker = breaker),
                    )
                repeat(5) {
                    resilience
                        .execute(deadline = 1.seconds) {
                            delay(1.hours)
                            success()
                        }.errorOrFail() shouldBe DeadlineExceeded("inventory", 1.seconds, DeadlineSource.OWN)
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }

            test("Half-Open の試行が呼び出し元の予算で打ち切られたら、枠を返して Half-Open のまま次の呼び出しに試させる") {
                val start = testScheduler.timeSource.markNow()
                val config = BASE.copy(attemptTimeout = 1.seconds, retry = null, circuitBreaker = breaker)
                val listener = RecordingListener()
                val resilience = testScheduler.resilience(config = config, listener = listener)
                repeat(5) { resilience.execute { failure() } }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
                delay(breaker.openDuration - start.elapsedNow())

                val probe =
                    withCallDeadline(50.milliseconds, testScheduler.timeSource) {
                        resilience.execute {
                            delay(200.milliseconds)
                            success()
                        }
                    }
                probe.errorOrFail() shouldBe DeadlineExceeded("inventory", 50.milliseconds, DeadlineSource.CALLER)
                resilience.circuitBreaker?.state shouldBe CircuitState.HALF_OPEN

                // 枠(halfOpenPermits = 1)が返っているので、次の呼び出しが試せる。成功すれば Closed
                resilience.execute { success() } shouldBe success()
                resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
                listener.transitions shouldContainExactly
                    listOf(
                        CircuitState.CLOSED to CircuitState.OPEN,
                        CircuitState.OPEN to CircuitState.HALF_OPEN,
                        CircuitState.HALF_OPEN to CircuitState.CLOSED,
                    )
            }

            test("入れ子: 外側の試行の時間切れで内側が打ち切られても、外側は自分の attemptTimeout の失敗として数える") {
                val outer =
                    testScheduler.resilience(
                        "downstream",
                        BASE.copy(attemptTimeout = 1.seconds, retry = null, circuitBreaker = breaker),
                    )
                val inner = testScheduler.resilience("oauth-token-endpoint", BASE.copy(deadline = 10.seconds, retry = null))
                repeat(5) {
                    outer
                        .execute {
                            inner.execute {
                                delay(1.hours)
                                success()
                            }
                        }.errorOrFail() shouldBe AttemptTimedOut("downstream", 1.seconds)
                }
                outer.circuitBreaker?.state shouldBe CircuitState.OPEN
            }
        }
    })
