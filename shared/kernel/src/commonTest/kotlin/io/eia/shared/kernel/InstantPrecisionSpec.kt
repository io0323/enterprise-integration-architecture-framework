package io.eia.shared.kernel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class InstantPrecisionSpec :
    FunSpec({
        test("マイクロ秒未満を切り捨てる(四捨五入しない)") {
            Instant.parse("2026-10-01T01:30:57.538668505Z").truncatedToMicros() shouldBe Instant.parse("2026-10-01T01:30:57.538668Z")
            Instant.parse("2026-10-01T01:30:57.538668999Z").truncatedToMicros() shouldBe Instant.parse("2026-10-01T01:30:57.538668Z")
        }

        test("エポック以前の時刻も過去方向に切り捨てる") {
            Instant.fromEpochSeconds(-1, 999_999_999).truncatedToMicros() shouldBe Instant.fromEpochSeconds(-1, 999_999_000)
        }

        test("マイクロ秒の精度の時刻はそのまま(2 回通しても同じ)") {
            val micros = Instant.parse("2026-10-01T01:30:57.538668Z")
            micros.truncatedToMicros() shouldBe micros
            micros.truncatedToMicros().truncatedToMicros() shouldBe micros
        }

        test("範囲の端でも秒は変えず、端数だけを落とす") {
            listOf(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE).forEach { edge ->
                val truncated = Instant.fromEpochSeconds(edge.epochSeconds, 999_999_999).truncatedToMicros()
                truncated shouldBe Instant.fromEpochSeconds(edge.epochSeconds, 999_999_000)
            }
        }
    })
