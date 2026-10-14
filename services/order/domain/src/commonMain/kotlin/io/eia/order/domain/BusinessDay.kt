package io.eia.order.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * 業務の 1 日(日本時間 Asia/Tokyo の 00:00 から翌日の 00:00 まで。ADR-0031 §1)。
 * order-service の日次の集計と、分析(batch-etl)の日次のパーティションは、同じこの区切りを使う(照合がずれないように)。
 */
@JvmInline
public value class BusinessDay private constructor(
    public val date: LocalDate,
) {
    /** 区間の始まり(含む)。 */
    public val start: Instant get() = date.atStartOfDayIn(TIME_ZONE)

    /** 区間の終わり(含まない)。翌日の始まり。 */
    public val end: Instant get() = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TIME_ZONE)

    override fun toString(): String = date.toString()

    public companion object {
        public const val TIME_ZONE_ID: String = "Asia/Tokyo"
        public val TIME_ZONE: TimeZone = TimeZone.of(TIME_ZONE_ID)
        private val FORMAT = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /** `YYYY-MM-DD`(日本時間の日付)を読む。存在しない日付(2 月 30 日など)は拒否する。 */
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

        /** [instant] が属する業務の日。 */
        public fun of(instant: Instant): BusinessDay = BusinessDay(instant.toLocalDateTime(TIME_ZONE).date)
    }
}

/**
 * 日次の集計のハッシュの 1 行(契約 `GET /v1/order-summaries/daily/{date}` の digest。ADR-0031 §3)。
 * 行を注文 ID のバイト順(UTF-8)に並べて `\n` でつなぎ、SHA-256 を取る(ハッシュの計算は adapters。domain は形式だけを持つ)。
 * 分析の側も同じ形式で作って比べる。
 */
public object DailyOrderDigest {
    public const val LINE_SEPARATOR: String = "\n"

    public fun line(
        orderId: String,
        status: OrderStatus,
        totalMinorUnits: Long,
        currency: String,
    ): String = "$orderId\t${status.name}\t$totalMinorUnits\t$currency"
}
