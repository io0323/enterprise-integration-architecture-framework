package io.eia.batchetl.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * 業務の 1 日(日本時間 Asia/Tokyo の 00:00 から翌日の 00:00 まで。ADR-0031 §1)。分析の日次のパーティションの単位。
 * order-service の日次の集計(`GET /v1/order-summaries/daily/{date}`)と同じ区切り(照合がずれないように)。
 * 区切りは契約(集計の応答の `timeZone`)にも書いてある。サービスの domain は互いに依存しないので、同じ定義を持つ。
 */
@JvmInline
public value class BusinessDay private constructor(
    public val date: LocalDate,
) : Comparable<BusinessDay> {
    public fun minusDays(days: Int): BusinessDay = BusinessDay(date.minus(days, DateTimeUnit.DAY))

    override fun compareTo(other: BusinessDay): Int = date.compareTo(other.date)

    override fun toString(): String = date.toString()

    public companion object {
        public const val TIME_ZONE_ID: String = "Asia/Tokyo"
        public val TIME_ZONE: TimeZone = TimeZone.of(TIME_ZONE_ID)
        private val FORMAT = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /** `YYYY-MM-DD`(日本時間の日付)を読む。存在しない日付は拒否する。 */
        public fun parse(
            text: String,
            field: String = "date",
        ): Result<BusinessDay, ValidationError> {
            if (!FORMAT.matches(text)) return err(ValidationError.of(field, "YYYY-MM-DD の形にしてください"))
            return try {
                ok(BusinessDay(LocalDate.parse(text)))
            } catch (_: IllegalArgumentException) {
                err(ValidationError.of(field, "存在しない日付です"))
            }
        }

        public fun of(date: LocalDate): BusinessDay = BusinessDay(date)

        /** [instant] が属する業務の日。 */
        public fun of(instant: Instant): BusinessDay = BusinessDay(instant.toLocalDateTime(TIME_ZONE).date)
    }
}
