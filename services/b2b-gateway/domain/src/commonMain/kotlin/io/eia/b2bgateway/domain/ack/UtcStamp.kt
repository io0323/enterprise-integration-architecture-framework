package io.eia.b2bgateway.domain.ack

import kotlin.time.Instant

/**
 * 応答の UNB の S004(0017 = YYMMDD、0019 = HHMM)。UTC で表す(取引先と時差を取り違えないため。ADR-0041 §5)。
 * commonMain では日付のライブラリを使わないので、1970-01-01 からの日数を暦の日付に直す(proleptic グレゴリオ暦)。
 */
public data class UtcStamp(
    public val date: String,
    public val time: String,
) {
    public companion object {
        private const val SECONDS_PER_DAY = 86_400L
        private const val SECONDS_PER_HOUR = 3_600L
        private const val SECONDS_PER_MINUTE = 60L
        private const val CENTURY = 100

        // 0000-03-01 から 1970-01-01 までの日数と、400 年(1 つの周期)の日数
        private const val DAYS_FROM_0000_03_01 = 719_468L
        private const val DAYS_PER_ERA = 146_097L

        public fun of(instant: Instant): UtcStamp {
            val days = instant.epochSeconds.floorDiv(SECONDS_PER_DAY)
            val seconds = instant.epochSeconds.mod(SECONDS_PER_DAY)
            val (year, month, day) = civil(days)
            val hour = seconds / SECONDS_PER_HOUR
            val minute = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
            return UtcStamp(
                date = "${two(year % CENTURY)}${two(month.toLong())}${two(day.toLong())}",
                time = "${two(hour)}${two(minute)}",
            )
        }

        @Suppress("MagicNumber") // 暦の計算の定数(H. Hinnant の civil_from_days)
        private fun civil(daysSinceEpoch: Long): Triple<Long, Int, Int> {
            val z = daysSinceEpoch + DAYS_FROM_0000_03_01
            val era = z.floorDiv(DAYS_PER_ERA)
            val dayOfEra = z - era * DAYS_PER_ERA
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val monthIndex = (5 * dayOfYear + 2) / 153
            val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
            val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return Triple(year, month, day)
        }

        private fun two(value: Long): String = value.toString().padStart(2, '0')
    }
}
