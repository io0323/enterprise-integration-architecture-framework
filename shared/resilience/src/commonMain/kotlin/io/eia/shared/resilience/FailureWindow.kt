package io.eia.shared.resilience

import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/** Closed の間の成否を集計する。スレッド安全ではないので、[CircuitBreaker] のロックの中で使う。 */
internal sealed class FailureWindow {
    abstract fun record(failed: Boolean)

    /** 窓の中の件数と失敗数。 */
    abstract fun snapshot(): Counts

    data class Counts(
        val total: Int,
        val failures: Int,
    )

    companion object {
        fun of(
            window: SlidingWindow,
            timeSource: TimeSource.WithComparableMarks,
        ): FailureWindow =
            when (window) {
                is SlidingWindow.Count -> CountWindow(window.size)
                is SlidingWindow.Time -> TimeWindow(window, timeSource)
            }
    }
}

/** 直近 [size] 件の環状バッファ。 */
internal class CountWindow(
    private val size: Int,
) : FailureWindow() {
    private val failed = BooleanArray(size)
    private var next = 0
    private var total = 0
    private var failures = 0

    override fun record(failed: Boolean) {
        if (total == size && this.failed[next]) failures--
        this.failed[next] = failed
        if (failed) failures++
        next = (next + 1) % size
        if (total < size) total++
    }

    override fun snapshot(): Counts = Counts(total, failures)
}

/** 直近の時間を固定幅の区間に分けて集計する。区間の番号は、窓を作った時刻からの経過時間で決める。 */
internal class TimeWindow(
    window: SlidingWindow.Time,
    timeSource: TimeSource.WithComparableMarks,
) : FailureWindow() {
    private val bucketNanos = (window.duration / window.buckets).inWholeNanoseconds
    private val start: ComparableTimeMark = timeSource.markNow()
    private val epochs = LongArray(window.buckets) { -1 }
    private val totals = IntArray(window.buckets)
    private val failures = IntArray(window.buckets)

    override fun record(failed: Boolean) {
        val epoch = currentEpoch()
        val slot = (epoch % epochs.size).toInt()
        if (epochs[slot] != epoch) {
            epochs[slot] = epoch
            totals[slot] = 0
            failures[slot] = 0
        }
        totals[slot]++
        if (failed) failures[slot]++
    }

    override fun snapshot(): Counts {
        val oldest = currentEpoch() - epochs.size + 1
        var total = 0
        var failed = 0
        for (slot in epochs.indices) {
            if (epochs[slot] >= oldest) {
                total += totals[slot]
                failed += failures[slot]
            }
        }
        return Counts(total, failed)
    }

    private fun currentEpoch(): Long = start.elapsedNow().inWholeNanoseconds / bucketNanos
}
