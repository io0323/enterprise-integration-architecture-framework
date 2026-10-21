package io.eia.saasmock.app

import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * CRM の API の Rate Limit(Token Bucket)。[requests] 回を [per] の間に使える。超えたら 429 と `Retry-After`(秒。切り上げ)を返す。
 * 管理の API で設定を変えられる(E2E で 429 を起こすため)。
 */
class RateLimiter(
    requests: Int,
    per: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var limit = Limit(requests, per)
    private var tokens = requests.toDouble()
    private var last: TimeMark = timeSource.markNow()

    data class Limit(
        val requests: Int,
        val per: Duration,
    ) {
        init {
            require(requests >= 1) { "requests は 1 以上です" }
            require(per.isPositive()) { "per は正の期間です" }
        }
    }

    /** 使えれば null、使えなければ再試行までの秒数。 */
    @Synchronized
    fun acquire(): Long? {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            null
        } else {
            val perToken = limit.per.toDouble(DurationUnit.SECONDS) / limit.requests
            ceil((1.0 - tokens) * perToken).toLong().coerceAtLeast(1)
        }
    }

    @Synchronized
    fun change(next: Limit) {
        refill()
        limit = next
        tokens = min(tokens, next.requests.toDouble())
    }

    @Synchronized
    fun current(): Limit = limit

    private fun refill() {
        val elapsed = last.elapsedNow()
        last = timeSource.markNow()
        val rate = limit.requests / limit.per.toDouble(DurationUnit.SECONDS)
        tokens = min(limit.requests.toDouble(), tokens + elapsed.toDouble(DurationUnit.SECONDS) * rate)
    }

    companion object {
        /** `<回数>/<期間>`(例 `20/1s`)を読む。読めなければ null。 */
        fun parse(text: String): Limit? {
            val parts = text.split('/', limit = 2)
            val requests =
                parts
                    .getOrNull(0)
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it >= 1 }
            val per =
                parts
                    .getOrNull(1)
                    ?.trim()
                    ?.let(Duration::parseOrNull)
                    ?.takeIf { it.isPositive() }
            return if (requests != null && per != null) Limit(requests, per) else null
        }

        val DEFAULT: Limit = Limit(20, 1.seconds)
    }
}
