@file:OptIn(ExperimentalCoroutinesApi::class)

package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 待ち時間を固定した RetryPolicy(100ms → 200ms → 400ms ...)。 */
private val FIXED = RetryPolicy(initialDelay = 100.milliseconds, multiplier = 2.0, maxAttempts = 3, jitter = Jitter.NONE)

private val BASE =
    ResilienceConfig(
        attemptTimeout = 1.seconds,
        retry = FIXED,
        retryBudget = null,
        circuitBreaker = null,
    )

private fun TestCoroutineScheduler.resilience(
    config: ResilienceConfig = BASE,
    listener: ResilienceListener = ResilienceListener.NONE,
    random: Random = Random(0),
) = Resilience("inventory", config, timeSource, random, listener)

/** 呼ばれた回数を数え、[results] を順に返す(尽きたら最後の値を返し続ける)。 */
private class Script(
    vararg results: Result<String, DomainError>,
) {
    private val queue = results.toList()
    var calls = 0

    fun next(): Result<String, DomainError> = queue[minOf(calls++, queue.lastIndex)]
}

class ResilienceSpec :
    FunSpec({
        // 同じ context の中のテストは仮想時間を共有するので、時刻は各テストの開始(start)からの経過で比べる
        coroutineTestScope = true

        context("Retry") {
            test("Retryable な失敗は RetryPolicy の待ち時間でリトライする") {
                val start = testScheduler.timeSource.markNow()
                val listener = RecordingListener()
                val script = Script(failure(), failure(), success())
                testScheduler.resilience(listener = listener).execute { script.next() } shouldBe ok("ok")
                script.calls shouldBe 3
                listener.retryDelays shouldContainExactly listOf(100.milliseconds, 200.milliseconds)
                start.elapsedNow() shouldBe 300.milliseconds
            }

            test("Jitter の乱数は注入した Random を使い、待ち時間は kernel の RetryPolicy と同じ計算になる") {
                val policy = RetryPolicy.DEFAULT
                val expected = Random(42).let { r -> listOf(policy.backoff(1, r), policy.backoff(2, r)) }
                val listener = RecordingListener()
                val resilience = testScheduler.resilience(BASE.copy(retry = policy), listener, Random(42))
                resilience.execute { failure() }
                listener.retryDelays shouldContainExactly expected
            }

            test("Retry-After があれば、バックオフより優先して待つ") {
                val listener = RecordingListener()
                val script = Script(failure(UnavailableError("混雑", retryAfter = 2.seconds)), success())
                testScheduler.resilience(listener = listener).execute { script.next() } shouldBe ok("ok")
                listener.retryDelays shouldContainExactly listOf(2.seconds)
            }

            test("NonRetryable はリトライしない") {
                val script = Script(failure(INVALID))
                testScheduler.resilience().execute { script.next() }.errorOrFail() shouldBe INVALID
                script.calls shouldBe 1
            }

            test("maxAttempts(初回を含む)に達したら、最後のエラーを返す") {
                val script = Script(failure())
                testScheduler.resilience().execute { script.next() }.errorOrFail() shouldBe UNAVAILABLE
                script.calls shouldBe 3
            }

            test("呼び出しごとに RetryPolicy を上書きできる(冪等でない呼び出しは null でリトライを止める)") {
                val script = Script(failure())
                val resilience = testScheduler.resilience()
                resilience.execute(retry = null) { script.next() }
                script.calls shouldBe 1
                resilience.execute(retry = FIXED.copy(maxAttempts = 2)) { script.next() }
                script.calls shouldBe 3
            }

            test("retry が null ならリトライしない") {
                val script = Script(failure())
                testScheduler.resilience(BASE.copy(retry = null)).execute { script.next() }
                script.calls shouldBe 1
            }
        }

        context("Timeout") {
            test("1 回の試行が attemptTimeout を超えたら AttemptTimedOut にし、リトライする") {
                val start = testScheduler.timeSource.markNow()
                val listener = RecordingListener()
                var calls = 0
                val result =
                    testScheduler.resilience(listener = listener).execute {
                        calls++
                        delay(5.seconds)
                        success()
                    }
                result.errorOrFail() shouldBe AttemptTimedOut("inventory", 1.seconds)
                calls shouldBe 3
                start.elapsedNow() shouldBe 3_300.milliseconds // 1s + 100ms + 1s + 200ms + 1s
                listener.events.count { it == "timeout:timeout" } shouldBe 3
            }

            test("呼び出し側のキャンセル(外側の withTimeout)は AttemptTimedOut にせず、そのまま伝える") {
                val resilience = testScheduler.resilience(BASE.copy(attemptTimeout = 10.seconds))
                shouldThrow<TimeoutCancellationException> {
                    withTimeout(500.milliseconds) {
                        resilience.execute {
                            delay(5.seconds)
                            success()
                        }
                    }
                }
            }

            test("block の中の別のタイムアウトは、自分の期限切れと取り違えずに伝える") {
                shouldThrow<TimeoutCancellationException> {
                    testScheduler.resilience().execute {
                        withTimeout(100.milliseconds) { delay(500.milliseconds) }
                        success()
                    }
                }
            }

            test("呼び出し側がキャンセルしたら、リトライの待機も止まる") {
                val start = testScheduler.timeSource.markNow()
                val script = Script(failure())
                coroutineScope {
                    val resilience = testScheduler.resilience(BASE.copy(retry = FIXED.copy(initialDelay = 10.seconds)))
                    val job = launch { resilience.execute { script.next() } }
                    delay(1.seconds)
                    job.cancel()
                }
                script.calls shouldBe 1
                start.elapsedNow() shouldBe 1_000.milliseconds
            }
        }

        context("締め切り(タイムバジェット)") {
            test("待ち時間が締め切りまでの残り時間を超えるリトライはしない") {
                val start = testScheduler.timeSource.markNow()
                val listener = RecordingListener()
                val script = Script(failure())
                val config = BASE.copy(deadline = 1.seconds, retry = FIXED.copy(initialDelay = 2.seconds, maxDelay = 30.seconds))
                testScheduler.resilience(config, listener).execute { script.next() }.errorOrFail() shouldBe UNAVAILABLE
                script.calls shouldBe 1
                start.elapsedNow() shouldBe 0.milliseconds
                listener.events shouldContainExactly listOf("suppressed:DEADLINE")
            }

            test("試行の途中で締め切りを過ぎたら DeadlineExceeded を返す") {
                val start = testScheduler.timeSource.markNow()
                val listener = RecordingListener()
                val config = BASE.copy(attemptTimeout = 5.seconds, deadline = 1.seconds)
                val result =
                    testScheduler.resilience(config, listener).execute {
                        delay(2.seconds)
                        success()
                    }
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", 1.seconds)
                start.elapsedNow() shouldBe 1_000.milliseconds
                listener.events shouldContainExactly listOf("timeout:deadline_exceeded")
            }

            test("残り時間が 0 以下なら、呼び出さずに DeadlineExceeded を返す(例外にしない)") {
                var called = false
                val result =
                    testScheduler.resilience().execute(deadline = Duration.ZERO) {
                        called = true
                        success()
                    }
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", Duration.ZERO)
                called shouldBe false
            }

            test("試行の Timeout を締め切りの残り時間まで縮め、締め切りで打ち切った試行も Circuit Breaker の失敗に数える") {
                val breaker = CircuitBreakerConfig(window = SlidingWindow.Count(2), minimumCalls = 2, halfOpenPermits = 1)
                val config = BASE.copy(attemptTimeout = 5.seconds, circuitBreaker = breaker)
                val resilience = testScheduler.resilience(config)
                repeat(2) {
                    val result =
                        resilience.execute(deadline = 2.seconds) {
                            delay(1.hours) // ハングした依存先
                            success()
                        }
                    result.errorOrFail() shouldBe DeadlineExceeded("inventory", 2.seconds)
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }

            test("残り時間でリトライの待ちと試行を打ち切る(試行の途中で残り時間を使い切る)") {
                val start = testScheduler.timeSource.markNow()
                var calls = 0
                val config = BASE.copy(attemptTimeout = 5.seconds, deadline = 1.seconds)
                val result =
                    testScheduler.resilience(config).execute {
                        calls++
                        if (calls == 1) failure() else delay(10.seconds).let { success() }
                    }
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", 1.seconds)
                calls shouldBe 2
                start.elapsedNow() shouldBe 1.seconds
            }

            test("Bulkhead の空きを待つ時間も、締め切りの残り時間までに縮める") {
                val config = BASE.copy(bulkhead = BulkheadConfig(maxConcurrentCalls = 1, maxWait = 10.seconds))
                val resilience = testScheduler.resilience(config)
                coroutineScope {
                    launch {
                        resilience.execute {
                            delay(1.minutes)
                            success()
                        }
                    }
                    testScheduler.runCurrent()
                    val start = testScheduler.timeSource.markNow()
                    resilience.execute(deadline = 500.milliseconds) { success() }.errorOrFail() shouldBe BulkheadFull("inventory")
                    start.elapsedNow() shouldBe 500.milliseconds
                }
            }

            test("呼び出しごとの締め切りで、設定の締め切りを上書きできる") {
                val config = BASE.copy(attemptTimeout = 5.seconds, deadline = 10.seconds)
                val result =
                    testScheduler.resilience(config).execute(deadline = 300.milliseconds) {
                        delay(2.seconds)
                        success()
                    }
                result.errorOrFail() shouldBe DeadlineExceeded("inventory", 300.milliseconds)
            }
        }

        context("Circuit Breaker との組み合わせ") {
            val breakerConfig =
                CircuitBreakerConfig(
                    window = SlidingWindow.Count(2),
                    minimumCalls = 2,
                    openDuration = 30.seconds,
                    halfOpenPermits = 1,
                )

            test("リトライの途中で Circuit Breaker が開いたら、待たずに返す") {
                val start = testScheduler.timeSource.markNow()
                val listener = RecordingListener()
                val script = Script(failure())
                val config = BASE.copy(retry = FIXED.copy(maxAttempts = 5), circuitBreaker = breakerConfig)
                val resilience = testScheduler.resilience(config, listener)
                resilience.execute { script.next() }.errorOrFail() shouldBe UNAVAILABLE
                script.calls shouldBe 2
                listener.events shouldContainExactly
                    listOf("retry:1", "transition:CLOSED->OPEN", "suppressed:CIRCUIT_OPEN")
                start.elapsedNow() shouldBe 100.milliseconds
            }

            test("Circuit Breaker が開いているときは、呼び出さずリトライもせずに CircuitOpen を返す") {
                val listener = RecordingListener()
                val script = Script(failure())
                val config = BASE.copy(retry = null, circuitBreaker = breakerConfig)
                val resilience = testScheduler.resilience(config, listener)
                repeat(2) { resilience.execute { script.next() } }
                listener.events.clear()
                resilience
                    .execute { script.next() }
                    .errorOrFail() shouldBe CircuitOpen("inventory", 30.seconds)
                script.calls shouldBe 2
                listener.events shouldContainExactly listOf("rejected:circuit_open")
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }

            test("タイムアウトは Circuit Breaker の失敗に数える") {
                val config = BASE.copy(retry = null, circuitBreaker = breakerConfig)
                val resilience = testScheduler.resilience(config)
                repeat(2) {
                    resilience.execute {
                        delay(5.seconds)
                        success()
                    }
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }
        }

        context("リトライバジェット") {
            test("残高を使い切ったらリトライしない") {
                val listener = RecordingListener()
                val script = Script(failure())
                val config = BASE.copy(retryBudget = RetryBudgetConfig(maxTokens = 4, tokenRatio = 0.1))
                val resilience = testScheduler.resilience(config, listener)
                resilience.execute { script.next() } // 失敗 2 回で残高 4 → 2(半分)になり、2 回目のリトライを見送る
                script.calls shouldBe 2
                listener.events shouldContainExactly listOf("retry:1", "suppressed:BUDGET_EXHAUSTED")
                listener.events.clear()
                resilience.execute { script.next() }
                script.calls shouldBe 3
                listener.events shouldContainExactly listOf("suppressed:BUDGET_EXHAUSTED")
            }

            test("残高を読める(設定がなければ null)") {
                val config = BASE.copy(retryBudget = RetryBudgetConfig(maxTokens = 4, tokenRatio = 0.1))
                val resilience = testScheduler.resilience(config)
                resilience.retryBudgetTokens shouldBe 4.0
                resilience.execute { failure() } // 失敗 2 回(2 回目のリトライは見送る)
                resilience.retryBudgetTokens shouldBe 2.0
                testScheduler.resilience().retryBudgetTokens shouldBe null
            }
        }

        context("Bulkhead との組み合わせ") {
            test("Bulkhead の拒否はリトライせず、待たずにすぐ返す") {
                val listener = RecordingListener()
                val config = BASE.copy(bulkhead = BulkheadConfig(maxConcurrentCalls = 1))
                val resilience = testScheduler.resilience(config, listener)
                coroutineScope {
                    launch {
                        // 枠を押さえる呼び出し。attemptTimeout(1 秒)より短くし、タイムアウトの失敗を混ぜない
                        resilience.execute {
                            delay(500.milliseconds)
                            success()
                        }
                    }
                    testScheduler.runCurrent()
                    val start = testScheduler.timeSource.markNow()
                    var calls = 0
                    resilience
                        .execute {
                            calls++
                            success()
                        }.errorOrFail() shouldBe BulkheadFull("inventory")
                    calls shouldBe 0
                    start.elapsedNow() shouldBe Duration.ZERO
                    listener.events shouldContainExactly listOf("rejected:bulkhead_full")
                }
            }

            test("Bulkhead の拒否は Circuit Breaker の失敗にもリトライバジェットにも数えない") {
                // 拒否を失敗に数えていれば、2 件で失敗率 100% になり開く
                val breaker =
                    CircuitBreakerConfig(window = SlidingWindow.Count(2), minimumCalls = 2, failureRateThreshold = 1.0, halfOpenPermits = 1)
                val config =
                    BASE.copy(
                        bulkhead = BulkheadConfig(maxConcurrentCalls = 1),
                        circuitBreaker = breaker,
                        retryBudget = RetryBudgetConfig(maxTokens = 3, tokenRatio = 0.1),
                    )
                val listener = RecordingListener()
                val resilience = testScheduler.resilience(config, listener)
                coroutineScope {
                    launch {
                        // 枠を押さえる呼び出し。attemptTimeout(1 秒)より短くし、タイムアウトの失敗を混ぜない
                        resilience.execute {
                            delay(500.milliseconds)
                            success()
                        }
                    }
                    testScheduler.runCurrent()
                    repeat(10) { resilience.execute { success() }.errorOrFail() shouldBe BulkheadFull("inventory") }
                }
                resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
                listener.transitions shouldBe emptyList()
                // バジェットが減っていなければ、失敗 1 回の後も残高 2(> 1.5)でリトライできる。拒否 10 件で減っていれば残高 0 で見送る
                listener.events.clear()
                val script = Script(failure(), success())
                resilience.execute { script.next() } shouldBe ok("ok")
                listener.events shouldContainExactly listOf("retry:1")
            }
        }

        context("Fallback") {
            test("最終的なエラーが対象なら代替の結果を返す") {
                val listener = RecordingListener()
                val result =
                    testScheduler
                        .resilience(BASE.copy(retry = null), listener)
                        .execute(Fallback.whenUnavailable { ok("cached") }) { failure() }
                result shouldBe ok("cached")
                listener.events shouldContainExactly listOf("fallback:unavailable")
            }

            test("対象でないエラー(業務エラー)は代替せずにそのまま返す") {
                val result =
                    testScheduler
                        .resilience()
                        .execute(Fallback.whenUnavailable { ok("cached") }) { failure(INVALID) }
                result.errorOrFail() shouldBe INVALID
            }

            test("成功したら代替しない") {
                testScheduler.resilience().execute(Fallback.whenUnavailable { ok("cached") }) { success() } shouldBe ok("ok")
            }
        }

        test("不正な設定は拒否する") {
            shouldThrow<IllegalArgumentException> { ResilienceConfig(attemptTimeout = 0.seconds) }
            shouldThrow<IllegalArgumentException> { ResilienceConfig(attemptTimeout = 1.seconds, deadline = 0.seconds) }
        }
    })
