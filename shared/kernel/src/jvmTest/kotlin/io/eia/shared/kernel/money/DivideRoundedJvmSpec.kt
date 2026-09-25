package io.eia.shared.kernel.money

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal

/** common の丸め実装が java.math.BigDecimal と同じ結果になることを、JVM でのみ検証する。 */
class DivideRoundedJvmSpec :
    FunSpec({
        test("divideRounded は BigDecimal.divide と一致する") {
            checkAll(Arb.long(), Arb.long(1L..Long.MAX_VALUE), Arb.enum<RoundingMode>()) { numerator, denominator, mode ->
                val expected =
                    BigDecimal
                        .valueOf(numerator)
                        .divide(BigDecimal.valueOf(denominator), 0, java.math.RoundingMode.valueOf(mode.name))
                        .longValueExact()
                divideRounded(numerator, denominator, mode) shouldBe expected
            }
        }

        test("小さい値の組み合わせでも一致する(端数がちょうど半分の境界を含む)") {
            checkAll(Arb.long(-1_000L..1_000L), Arb.long(1L..20L), Arb.enum<RoundingMode>()) { numerator, denominator, mode ->
                val expected =
                    BigDecimal
                        .valueOf(numerator)
                        .divide(BigDecimal.valueOf(denominator), 0, java.math.RoundingMode.valueOf(mode.name))
                        .longValueExact()
                divideRounded(numerator, denominator, mode) shouldBe expected
            }
        }
    })
