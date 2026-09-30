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
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", 500.milliseconds)
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
                shorterCaller.errorOrFail() shouldBe DeadlineExceeded("inventory", 300.milliseconds)
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
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", Duration.ZERO)
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
    })
