package io.eia.shared.kernel.money

import io.eia.shared.kernel.shouldBeErr
import io.eia.shared.kernel.shouldBeOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CurrencyAndRateSpec :
    FunSpec({
        context("Currency") {
            test("定数の通貨はコードと小数桁数を持つ") {
                Currency.JPY.minorUnitDigits shouldBe 0
                Currency.USD.minorUnitDigits shouldBe 2
                Currency.KRW.code shouldBe "KRW"
                Currency.COMMON.map { it.code } shouldBe listOf("JPY", "USD", "EUR", "GBP", "CNY", "KRW")
            }

            test("定数以外の通貨もコードと小数桁数から生成でき、値で比較される") {
                val bhd = Currency.of("BHD", 3).shouldBeOk()
                bhd.toString() shouldBe "BHD"
                Currency.of("USD", 2).shouldBeOk() shouldBe Currency.USD
                Currency.of("USD", 2).shouldBeOk().hashCode() shouldBe Currency.USD.hashCode()
                Currency.of("USD", 3).shouldBeOk() shouldNotBe Currency.USD
            }

            test("不正なコードと小数桁数を拒否する") {
                listOf("usd", "US", "USDX", "U5D").forEach { Currency.of(it, 2).shouldBeErr().code shouldBe "validation_failed" }
                Currency.of("XXX", -1).shouldBeErr().code shouldBe "validation_failed"
                Currency.of("XXX", 5).shouldBeErr().code shouldBe "validation_failed"
            }

            test("CurrencyResolver は登録された通貨だけを解決する") {
                CurrencyResolver.COMMON.resolve("EUR") shouldBe Currency.EUR
                CurrencyResolver.COMMON.resolve("BHD").shouldBeNull()
                val bhd = Currency.of("BHD", 3).shouldBeOk()
                CurrencyResolver.of(Currency.COMMON + bhd).resolve("BHD") shouldBe bhd
            }
        }

        context("Rate") {
            test("既約分数で保持し、basis points と分数の表現は等しい") {
                val tenPercent = Rate.basisPoints(1000).shouldBeOk()
                tenPercent.numerator shouldBe 1L
                tenPercent.denominator shouldBe 10L
                tenPercent shouldBe Rate.of(1, 10).shouldBeOk()
                tenPercent.hashCode() shouldBe Rate.of(2, 20).shouldBeOk().hashCode()
                tenPercent.toString() shouldBe "1/10"
                Rate.of(0, 5).shouldBeOk() shouldBe Rate.ZERO
                Rate.basisPoints(10_000).shouldBeOk() shouldBe Rate.ONE
                Rate.basisPoints(800).shouldBeOk() shouldNotBe tenPercent
            }

            test("負の率と 0 以下の分母を拒否する") {
                Rate.of(-1, 10).shouldBeErr().code shouldBe "validation_failed"
                Rate.of(1, 0).shouldBeErr().code shouldBe "validation_failed"
                Rate.basisPoints(-1).shouldBeErr().code shouldBe "validation_failed"
            }
        }

        context("divideRounded(java.math.RoundingMode と同じ意味)") {
            // 被除数 / 4 の結果: 2.5, 1.5, 0.5, -0.5, -1.5, -2.5, 2.25, -2.75
            val dividends = listOf(10L, 6, 2, -2, -6, -10, 9, -11)
            withData(
                nameFn = { it.first.name },
                RoundingMode.UP to listOf(3L, 2, 1, -1, -2, -3, 3, -3),
                RoundingMode.DOWN to listOf(2L, 1, 0, 0, -1, -2, 2, -2),
                RoundingMode.CEILING to listOf(3L, 2, 1, 0, -1, -2, 3, -2),
                RoundingMode.FLOOR to listOf(2L, 1, 0, -1, -2, -3, 2, -3),
                RoundingMode.HALF_UP to listOf(3L, 2, 1, -1, -2, -3, 2, -3),
                RoundingMode.HALF_DOWN to listOf(2L, 1, 0, 0, -1, -2, 2, -3),
                RoundingMode.HALF_EVEN to listOf(2L, 2, 0, 0, -2, -2, 2, -3),
            ) { (mode, expected) ->
                dividends.map { divideRounded(it, 4, mode) } shouldBe expected
            }

            test("割り切れる場合は丸めない・分母は正") {
                RoundingMode.entries.forEach { divideRounded(12, 4, it) shouldBe 3L }
                shouldThrow<IllegalArgumentException> { divideRounded(1, 0, RoundingMode.DOWN) }
            }
        }
    })
