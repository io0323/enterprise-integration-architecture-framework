package io.eia.platform.batch.sla

import io.eia.platform.batch.PartitionKey
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/** パーティションごとの完了の期限。期限がないパーティションは null。 */
public fun interface Deadline {
    public fun of(partition: PartitionKey): Instant?
}

/**
 * DAG の SLA(Framework 7.2: 完了の期限を決め、遅れると予測した時点で知らせる。失敗の後ではなく)。
 */
public class Sla(
    public val deadline: Deadline,
) {
    public companion object {
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /**
         * 日次のパーティション(業務の日 `yyyy-MM-dd`)の期限を、その日の [daysAfter] 日後の [time]([zone] の時刻)にする。
         * 例: `dailyAt(LocalTime(6, 0), Asia/Tokyo)` は、2026-10-13 の分を 2026-10-14 06:00 JST までに終える。
         * 日付でないパーティションは期限なし。
         */
        public fun dailyAt(
            time: LocalTime,
            zone: TimeZone,
            daysAfter: Int = 1,
        ): Sla {
            require(daysAfter >= 0) { "daysAfter は 0 以上にしてください: $daysAfter" }
            return Sla { partition ->
                parseDate(partition.value)?.let { date -> LocalDateTime(date.plus(DatePeriod(days = daysAfter)), time).toInstant(zone) }
            }
        }

        private fun parseDate(value: String): LocalDate? {
            if (!DATE.matches(value)) return null
            return try {
                LocalDate.parse(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
