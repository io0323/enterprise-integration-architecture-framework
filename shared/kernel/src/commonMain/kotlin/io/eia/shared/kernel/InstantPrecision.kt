package io.eia.shared.kernel

import kotlin.time.Instant

private const val NANOS_PER_MICRO = 1_000

/**
 * マイクロ秒未満を切り捨てる(ADR-0012 §3)。永続化(PostgreSQL)やイベント(Avro の `timestamp-micros`)に載せる時刻は、
 * 受け付けた時点でこれを通し、読み戻した値と食い違わないようにする(CODING_STANDARDS「時刻」)。
 *
 * 切り捨ては時間軸の過去方向(負の無限大方向)で、エポック以前の時刻でも同じ。
 * [Instant.nanosecondsOfSecond] は常に 0 以上なので、秒の中の端数を落とせば過去方向になる。
 */
public fun Instant.truncatedToMicros(): Instant =
    Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / NANOS_PER_MICRO * NANOS_PER_MICRO)
