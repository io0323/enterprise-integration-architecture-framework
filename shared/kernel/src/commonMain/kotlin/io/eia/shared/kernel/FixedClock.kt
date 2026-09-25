package io.eia.shared.kernel

import kotlin.time.Clock
import kotlin.time.Instant

// 時刻の Port には標準ライブラリの kotlin.time.Clock を使う(ADR-0011)。本番は Clock.System、テストは FixedClock を注入する。
// 時刻は常に UTC の kotlin.time.Instant で保持し、タイムゾーンへの変換は表示時に行う(Framework 15.3)。

/** 常に同じ時刻を返す [Clock]。テストや再実行(同じ時刻で処理をやり直す)で使う。 */
public class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}
