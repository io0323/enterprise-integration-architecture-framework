package io.eia.shared.kernel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicySpec :
    FunSpec({
        val transient = UnavailableError("timeout")
        val permanent = ValidationError.of("quantity", "1 以上です")

        test("既定値は INTEGRATION_STANDARDS §3 のとおり(maxAttempts は初回を含む)") {
            RetryPolicy.DEFAULT shouldBe
                RetryPolicy(
                    initialDelay = 500.milliseconds,
                    multiplier = 2.0,
                    maxAttempts = 3,
                    maxDelay = 30.seconds,
                    jitter = Jitter.FULL,
                )
        }

        test("maxAttempts = 3 なら 1・2 回目の失敗はリトライし、3 回目で打ち切る") {
            val random = Random(0)
            (RetryPolicy.DEFAULT.decide(1, transient, random) is RetryDecision.Retry) shouldBe true
            (RetryPolicy.DEFAULT.decide(2, transient, random) is RetryDecision.Retry) shouldBe true
            RetryPolicy.DEFAULT.decide(3, transient, random) shouldBe RetryDecision.GiveUp
        }

        test("NonRetryable はリトライしない") {
            RetryPolicy.DEFAULT.decide(1, permanent, Random(0)) shouldBe RetryDecision.GiveUp
        }

        test("Retry-After をバックオフより優先し、maxDelay を超える場合は打ち切る") {
            RetryPolicy.DEFAULT.decide(1, UnavailableError("429", retryAfter = 2.seconds), Random(0)) shouldBe
                RetryDecision.Retry(2.seconds)
            RetryPolicy.DEFAULT.decide(1, UnavailableError("429", retryAfter = 31.seconds), Random(0)) shouldBe
                RetryDecision.GiveUp
        }

        test("Jitter なしなら 500ms, 1s, 2s, ... と倍増し、30s で頭打ちになる") {
            val policy = RetryPolicy(jitter = Jitter.NONE, maxAttempts = 10)
            (1..8).map { policy.backoff(it, Random(0)).inWholeMilliseconds } shouldBe
                listOf(500L, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000)
        }

        test("Full Jitter の待ち時間は [0, min(上限, 指数値)] に収まる") {
            checkAll(Arb.int(1..40), Arb.long()) { attempt, seed ->
                val ceiling = min(30_000.0, 500 * 2.0.pow(attempt - 1)).toLong()
                RetryPolicy.DEFAULT.backoff(attempt, Random(seed)).inWholeMilliseconds shouldBeInRange 0L..ceiling
            }
        }

        test("Equal Jitter の待ち時間は [上限/2, 上限] に収まる") {
            val policy = RetryPolicy(jitter = Jitter.EQUAL)
            checkAll(Arb.int(1..40), Arb.long()) { attempt, seed ->
                val ceiling = min(30_000.0, 500 * 2.0.pow(attempt - 1)).toLong()
                policy.backoff(attempt, Random(seed)).inWholeMilliseconds shouldBeInRange ceiling / 2..ceiling
            }
        }

        test("不正な設定と試行番号を拒否する") {
            shouldThrow<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
            shouldThrow<IllegalArgumentException> { RetryPolicy(multiplier = 0.5) }
            shouldThrow<IllegalArgumentException> { RetryPolicy(initialDelay = 0.milliseconds) }
            shouldThrow<IllegalArgumentException> { RetryPolicy(maxDelay = 100.milliseconds) }
            shouldThrow<IllegalArgumentException> { RetryPolicy.DEFAULT.decide(0, transient, Random(0)) }
            shouldThrow<IllegalArgumentException> { RetryPolicy.DEFAULT.backoff(0, Random(0)) }
        }
    })
