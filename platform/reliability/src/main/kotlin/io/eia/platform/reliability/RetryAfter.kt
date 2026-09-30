package io.eia.platform.reliability

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `Retry-After`(RFC 9110 §10.2.3)の解析。秒数か HTTP-date を受け付け、解釈できなければ null。
 * 結果は `DomainError.Retryable.retryAfter` に入れ、kernel の RetryPolicy が優先して使う(INTEGRATION_STANDARDS §3)。
 */
public object RetryAfter {
    private val DELTA_SECONDS = Regex("[0-9]{1,9}")

    /** [value] を待ち時間にする。HTTP-date は [now] からの差にし、過去の日時は 0 にする。 */
    public fun parse(
        value: String?,
        now: Instant,
    ): Duration? {
        val trimmed = value?.trim() ?: return null
        return if (DELTA_SECONDS.matches(trimmed)) trimmed.toLong().seconds else untilDate(trimmed, now)
    }

    private fun untilDate(
        value: String,
        now: Instant,
    ): Duration? =
        try {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (Instant.fromEpochMilliseconds(date.toEpochMilli()) - now).coerceAtLeast(Duration.ZERO)
        } catch (_: DateTimeParseException) {
            null
        }
}
