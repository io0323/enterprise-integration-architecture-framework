package io.eia.platform.batch

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 時刻での起動(日次・週次)。起動の時刻は [zone] の壁時計の時刻で決め、パーティションはその日の [partitionDaysBefore] 日前の業務の日にする
 * (例: 毎日 01:00 JST に前日の分を処理する)。
 *
 * [BatchScheduler] は、今までの直近の起動の時刻([latest])のパーティションの実行がなければ起動する。止まっていた間の起動は、
 * 直近の 1 回だけを取り戻す(それより前の日は、運用者が明示的に実行する。docs/runbooks/batch-rerun.md)。
 */
public class Schedule private constructor(
    private val at: LocalTime,
    private val zone: TimeZone,
    private val days: Set<DayOfWeek>,
    private val partitionDaysBefore: Int,
) {
    init {
        require(partitionDaysBefore >= 0) { "partitionDaysBefore は 0 以上にしてください: $partitionDaysBefore" }
    }

    /** [time] の起動と、そのパーティション。 */
    public data class Occurrence(
        val time: Instant,
        val partition: PartitionKey,
    )

    /** [now] 以前の直近の起動。 */
    public fun latest(now: Instant): Occurrence {
        val today = now.toLocalDateTime(zone).date
        // 週次でも 7 日さかのぼれば必ず見つかる(今日の起動の時刻がまだ来ていなければ、先週の同じ曜日)
        for (daysAgo in 0..DAYS_IN_WEEK) {
            val date = today.minus(DatePeriod(days = daysAgo))
            if (date.dayOfWeek !in days) continue
            val time = LocalDateTime(date, at).toInstant(zone)
            if (time <= now) return Occurrence(time, PartitionKey(date.minus(DatePeriod(days = partitionDaysBefore)).toString()))
        }
        error("起動の時刻が見つかりません")
    }

    public companion object {
        private const val DAYS_IN_WEEK = 7

        public fun daily(
            at: LocalTime,
            zone: TimeZone,
            partitionDaysBefore: Int = 1,
        ): Schedule = Schedule(at, zone, DayOfWeek.entries.toSet(), partitionDaysBefore)

        public fun weekly(
            day: DayOfWeek,
            at: LocalTime,
            zone: TimeZone,
            partitionDaysBefore: Int = 1,
        ): Schedule = Schedule(at, zone, setOf(day), partitionDaysBefore)
    }
}
