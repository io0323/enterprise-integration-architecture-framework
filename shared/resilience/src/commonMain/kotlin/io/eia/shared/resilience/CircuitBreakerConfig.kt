package io.eia.shared.resilience

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Circuit Breaker の設定(ADR-0021 §4)。
 *
 * @property window 失敗率を判定する窓(直近の件数か、直近の時間)
 * @property failureRateThreshold この割合以上が失敗なら Open にする(0 より大きく 1 以下)
 * @property minimumCalls 窓の中の件数がこれに満たないうちは判定しない(少数の失敗で開かないため)
 * @property openDuration Open を続ける時間。過ぎた後の最初の呼び出しで Half-Open にする
 * @property halfOpenPermits Half-Open で試す件数。すべて成功すれば Closed、1 件でも失敗すれば Open に戻す
 */
public data class CircuitBreakerConfig(
    public val window: SlidingWindow = SlidingWindow.Count(DEFAULT_WINDOW_SIZE),
    public val failureRateThreshold: Double = DEFAULT_FAILURE_RATE_THRESHOLD,
    public val minimumCalls: Int = DEFAULT_MINIMUM_CALLS,
    public val openDuration: Duration = 30.seconds,
    public val halfOpenPermits: Int = DEFAULT_HALF_OPEN_PERMITS,
) {
    init {
        require(failureRateThreshold > 0.0 && failureRateThreshold <= 1.0) {
            "failureRateThreshold は 0 より大きく 1 以下です: $failureRateThreshold"
        }
        require(minimumCalls >= 1) { "minimumCalls は 1 以上です: $minimumCalls" }
        require(openDuration.isPositive()) { "openDuration は正の値です: $openDuration" }
        require(halfOpenPermits >= 1) { "halfOpenPermits は 1 以上です: $halfOpenPermits" }
        if (window is SlidingWindow.Count) {
            require(minimumCalls <= window.size) { "minimumCalls($minimumCalls)は窓の件数(${window.size})以下です" }
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_SIZE = 20
        const val DEFAULT_FAILURE_RATE_THRESHOLD = 0.5
        const val DEFAULT_MINIMUM_CALLS = 10
        const val DEFAULT_HALF_OPEN_PERMITS = 3
    }
}

/** 失敗率を判定する窓。 */
public sealed interface SlidingWindow {
    /** 直近 [size] 件。 */
    public data class Count(
        public val size: Int,
    ) : SlidingWindow {
        init {
            require(size >= 1) { "size は 1 以上です: $size" }
        }
    }

    /**
     * 直近 [duration] の間。[buckets] 個の区間で集計し、古い区間から捨てる(記録ごとに保持しないので、件数が多くても記憶量は一定)。
     * 窓の端は区間の幅([duration] / [buckets])の精度で動く。
     */
    public data class Time(
        public val duration: Duration,
        public val buckets: Int = DEFAULT_BUCKETS,
    ) : SlidingWindow {
        init {
            require(duration.isPositive()) { "duration は正の値です: $duration" }
            require(buckets >= 1) { "buckets は 1 以上です: $buckets" }
            require((duration / buckets).isPositive()) { "区間の幅が 0 になります: $duration / $buckets" }
        }

        private companion object {
            const val DEFAULT_BUCKETS = 10
        }
    }
}
