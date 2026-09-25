package io.eia.shared.kernel

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential Backoff + Jitter によるリトライの判定(Framework 5.5・13.1、INTEGRATION_STANDARDS §3)。
 *
 * 待ち時間の計算だけを行う純粋な関数で、実際の待機・再実行は `shared/resilience` が行う。
 * 乱数は引数で受け取り、テストで固定できるようにする。
 *
 * @property maxAttempts 初回を含む試行回数の上限(既定 3 = 初回 + リトライ 2 回)。ADR-0011。
 */
public data class RetryPolicy(
    public val initialDelay: Duration = 500.milliseconds,
    public val multiplier: Double = 2.0,
    public val maxAttempts: Int = 3,
    public val maxDelay: Duration = 30.seconds,
    public val jitter: Jitter = Jitter.FULL,
) {
    init {
        require(initialDelay.isPositive()) { "initialDelay は正の値です: $initialDelay" }
        require(multiplier >= 1.0) { "multiplier は 1.0 以上です: $multiplier" }
        require(maxAttempts >= 1) { "maxAttempts は 1 以上です: $maxAttempts" }
        require(maxDelay >= initialDelay) { "maxDelay は initialDelay 以上です: $maxDelay" }
    }

    /**
     * [attempt] 回目(1 始まり)の試行が [error] で失敗したときに、リトライするかと待ち時間を決める。
     *
     * - [DomainError.NonRetryable] と、[maxAttempts] 回目の失敗は [RetryDecision.GiveUp]。
     * - [DomainError.Retryable.retryAfter] があればバックオフより優先する。ただし [maxDelay] を超える場合は、
     *   タイムバジェットを超えるため待たずに [RetryDecision.GiveUp] とする。
     */
    public fun decide(
        attempt: Int,
        error: DomainError,
        random: Random,
    ): RetryDecision {
        require(attempt >= 1) { "attempt は 1 始まりです: $attempt" }
        if (error !is DomainError.Retryable || attempt >= maxAttempts) return RetryDecision.GiveUp
        val retryAfter = error.retryAfter
        return when {
            retryAfter == null -> RetryDecision.Retry(backoff(attempt, random))
            retryAfter > maxDelay -> RetryDecision.GiveUp
            else -> RetryDecision.Retry(retryAfter)
        }
    }

    /** [attempt] 回目の失敗後の待ち時間。上限値は `min(maxDelay, initialDelay * multiplier^(attempt-1))`。 */
    public fun backoff(
        attempt: Int,
        random: Random,
    ): Duration {
        require(attempt >= 1) { "attempt は 1 始まりです: $attempt" }
        val exponentialMillis = initialDelay.inWholeMilliseconds * multiplier.pow(attempt - 1)
        val ceilingMillis = min(exponentialMillis, maxDelay.inWholeMilliseconds.toDouble()).toLong()
        val delayMillis =
            when (jitter) {
                Jitter.NONE -> ceilingMillis
                Jitter.FULL -> random.nextLong(0, ceilingMillis + 1)
                Jitter.EQUAL -> ceilingMillis / 2 + random.nextLong(0, ceilingMillis - ceilingMillis / 2 + 1)
            }
        return delayMillis.milliseconds
    }

    public companion object {
        /** INTEGRATION_STANDARDS §3 の既定値。 */
        public val DEFAULT: RetryPolicy = RetryPolicy()
    }
}

public enum class Jitter {
    /** 揺らぎなし(テスト・単発呼出し用)。Retry Storm を招くため連携では使わない。 */
    NONE,

    /** `[0, 上限]` の一様乱数。既定。 */
    FULL,

    /** `[上限/2, 上限]` の一様乱数。最低限の待ち時間を保証したい場合に使う。 */
    EQUAL,
}

public sealed interface RetryDecision {
    public data class Retry(
        public val delay: Duration,
    ) : RetryDecision

    public data object GiveUp : RetryDecision
}
