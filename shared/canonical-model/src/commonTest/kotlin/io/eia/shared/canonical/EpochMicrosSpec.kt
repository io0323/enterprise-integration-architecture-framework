package io.eia.shared.canonical

import io.eia.shared.canonical.common.instantOfEpochMicros
import io.eia.shared.canonical.common.toEpochMicros
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

// ADR-0012: Instant ⇔ Avro timestamp-micros。マイクロ秒未満は過去方向に切り捨てる。
class EpochMicrosSpec :
    FunSpec({
        test("ナノ秒を含む値はマイクロ秒未満を切り捨てて変換し、往復するとマイクロ秒未満が 0 になる") {
            val instant = Instant.fromEpochSeconds(1_790_000_000, 123_456_789)

            val micros = instant.toEpochMicros().shouldBeOk()

            micros shouldBe 1_790_000_000_123_456L
            instantOfEpochMicros(micros) shouldBe Instant.fromEpochSeconds(1_790_000_000, 123_456_000)
        }

        test("マイクロ秒ちょうどの値は往復しても変わらない") {
            val instant = Instant.parse("2026-09-25T01:00:00.123456Z")

            instantOfEpochMicros(instant.toEpochMicros().shouldBeOk()) shouldBe instant
        }

        test("エポック以前の時刻も過去方向に切り捨てる") {
            // 1969-12-31T23:59:59.999999999Z(エポックの 1 ナノ秒前)
            val instant = Instant.fromEpochSeconds(-1, 999_999_999)

            val micros = instant.toEpochMicros().shouldBeOk()

            micros shouldBe -1L
            instantOfEpochMicros(micros) shouldBe Instant.fromEpochSeconds(-1, 999_999_000)
        }

        test("Long の上限と下限のマイクロ秒と、DISTANT_FUTURE / DISTANT_PAST(ナノ秒は切り捨て)は変換できる") {
            instantOfEpochMicros(Instant.DISTANT_FUTURE.toEpochMicros().shouldBeOk()) shouldBe Instant.DISTANT_FUTURE
            // DISTANT_PAST は ...59.999999999Z でナノ秒を含むため、往復するとマイクロ秒未満が落ちる
            instantOfEpochMicros(Instant.DISTANT_PAST.toEpochMicros().shouldBeOk()) shouldBe
                Instant.fromEpochSeconds(Instant.DISTANT_PAST.epochSeconds, 999_999_000)
            instantOfEpochMicros(Long.MAX_VALUE).toEpochMicros().shouldBeOk() shouldBe Long.MAX_VALUE
            instantOfEpochMicros(Long.MIN_VALUE).toEpochMicros().shouldBeOk() shouldBe Long.MIN_VALUE
        }

        test("timestamp-micros で表せない時刻はエラーにする") {
            // Instant の表現範囲の端(約 ±10 億年)。DISTANT_FUTURE / DISTANT_PAST(±10 万年)は範囲内に収まる。
            val error = Instant.fromEpochSeconds(Long.MAX_VALUE).toEpochMicros().shouldBeErr()

            error.violations.single().field shouldBe "timestamp"
            Instant.fromEpochSeconds(Long.MIN_VALUE).toEpochMicros().shouldBeErr()
            instantOfEpochMicros(Long.MAX_VALUE).plusMicros(1).toEpochMicros().shouldBeErr()
            instantOfEpochMicros(Long.MIN_VALUE).minusNanos(1).toEpochMicros().shouldBeErr()
        }
    })

private fun Instant.plusMicros(micros: Long): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond + micros * 1_000)

private fun Instant.minusNanos(nanos: Long): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond - nanos)
