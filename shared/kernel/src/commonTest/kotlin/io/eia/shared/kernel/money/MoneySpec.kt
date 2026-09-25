package io.eia.shared.kernel.money

import io.eia.shared.kernel.shouldBeErr
import io.eia.shared.kernel.shouldBeOk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

private fun usd(minor: Long) = Money.ofMinor(minor, Currency.USD)

private fun jpy(minor: Long) = Money.ofMinor(minor, Currency.JPY)

class MoneySpec :
    FunSpec({
        context("parse / toDecimalString") {
            // withData は native で成功したケースがレポートに出ないため、通常のテストとして登録する(全ターゲットで件数を揃える)
            listOf(
                Triple("1234.50", Currency.USD, 123_450L),
                Triple("1234.5", Currency.USD, 123_450L),
                Triple("0.01", Currency.USD, 1L),
                Triple("-0.01", Currency.USD, -1L),
                Triple("100", Currency.JPY, 100L),
                Triple("0007", Currency.JPY, 7L),
                Triple("-0", Currency.JPY, 0L),
            ).forEach { (text, currency, minor) ->
                test("$text $currency -> $minor") {
                    Money.parse(text, currency).shouldBeOk() shouldBe Money.ofMinor(minor, currency)
                }
            }

            test("小数桁数を超える値は丸めずにエラーにする") {
                Money.parse("100.5", Currency.JPY).shouldBeErr().message shouldBe "amount: 小数部が JPY の小数桁数(0)を超えています"
                Money.parse("1.005", Currency.USD).shouldBeErr().code shouldBe "validation_failed"
                Money.parse("100.0", Currency.JPY).shouldBeErr().code shouldBe "validation_failed"
            }

            test("10 進表記以外と範囲外の値を拒否する") {
                listOf("", "1e3", "1,000", " 1", "1.", ".5", "+1", "abc", "9223372036854775808").forEach {
                    Money.parse(it, Currency.JPY).shouldBeErr().code shouldBe "validation_failed"
                }
            }

            test("常に通貨の小数桁数で表記する") {
                usd(123_450).toDecimalString() shouldBe "1234.50"
                usd(5).toDecimalString() shouldBe "0.05"
                usd(-5).toDecimalString() shouldBe "-0.05"
                jpy(-1200).toDecimalString() shouldBe "-1200"
                Money.ofMinor(1, Currency.of("CLF", 4).shouldBeOk()).toDecimalString() shouldBe "0.0001"
                usd(Long.MIN_VALUE).toDecimalString() shouldBe "-92233720368547758.08"
                usd(199).toString() shouldBe "1.99 USD"
            }

            test("表記と読み取りは往復で一致する") {
                checkAll(Arb.long()) { minor ->
                    Money.parse(usd(minor).toDecimalString(), Currency.USD).let {
                        if (minor == Long.MIN_VALUE) it.shouldBeErr() else it.shouldBeOk() shouldBe usd(minor)
                    }
                }
            }
        }

        context("加減算と数量倍") {
            test("同じ通貨なら計算する") {
                (usd(150) + usd(275)).shouldBeOk() shouldBe usd(425)
                (usd(150) - usd(275)).shouldBeOk() shouldBe usd(-125)
                (usd(199) * 3).shouldBeOk() shouldBe usd(597)
                (-usd(199)).shouldBeOk() shouldBe usd(-199)
                Money.sum(Currency.USD, listOf(usd(1), usd(2), usd(3))).shouldBeOk() shouldBe usd(6)
                Money.sum(Currency.JPY, emptyList()).shouldBeOk() shouldBe Money.zero(Currency.JPY)
            }

            test("通貨が異なると CurrencyMismatch") {
                (usd(1) + jpy(1)).shouldBeErr() shouldBe MoneyError.CurrencyMismatch(Currency.USD, Currency.JPY)
                (usd(1) - jpy(1)).shouldBeErr().code shouldBe "currency_mismatch"
                Money.sum(Currency.USD, listOf(usd(1), jpy(1))).shouldBeErr().shouldBeInstanceOf<MoneyError.CurrencyMismatch>()
            }

            test("Long の範囲を超えると Overflow") {
                (usd(Long.MAX_VALUE) + usd(1)).shouldBeErr().code shouldBe "money_overflow"
                (usd(Long.MIN_VALUE) - usd(1)).shouldBeErr().code shouldBe "money_overflow"
                (usd(Long.MAX_VALUE / 2 + 1) * 2).shouldBeErr().code shouldBe "money_overflow"
                (usd(Long.MIN_VALUE) * -1).shouldBeErr().code shouldBe "money_overflow"
                (-usd(Long.MIN_VALUE)).shouldBeErr().code shouldBe "money_overflow"
                (usd(-1) * Long.MIN_VALUE).shouldBeErr().code shouldBe "money_overflow"
                (usd(0) * Long.MIN_VALUE).shouldBeOk() shouldBe usd(0)
            }

            test("状態を表すプロパティ") {
                usd(0).isZero shouldBe true
                usd(-1).isNegative shouldBe true
                usd(1).isNegative shouldBe false
                usd(1) shouldNotBe jpy(1)
                usd(1).hashCode() shouldBe usd(1).hashCode()
            }
        }

        context("率を掛ける") {
            val tenPercent = Rate.basisPoints(1000).shouldBeOk()

            test("丸め方を引数で指定する") {
                // 1,005 円 × 10% = 100.5 円
                jpy(1005).times(tenPercent, RoundingMode.DOWN).shouldBeOk() shouldBe jpy(100)
                jpy(1005).times(tenPercent, RoundingMode.HALF_UP).shouldBeOk() shouldBe jpy(101)
                jpy(1005).times(tenPercent, RoundingMode.HALF_EVEN).shouldBeOk() shouldBe jpy(100)
                jpy(1015).times(tenPercent, RoundingMode.HALF_EVEN).shouldBeOk() shouldBe jpy(102)
                jpy(1001).times(tenPercent, RoundingMode.UP).shouldBeOk() shouldBe jpy(101)
            }

            test("分数の率(1/3)も丸め方どおりに計算する") {
                usd(100).times(Rate.of(1, 3).shouldBeOk(), RoundingMode.HALF_UP).shouldBeOk() shouldBe usd(33)
                usd(100).times(Rate.of(2, 3).shouldBeOk(), RoundingMode.DOWN).shouldBeOk() shouldBe usd(66)
            }

            test("オーバーフローは Overflow") {
                usd(Long.MAX_VALUE).times(Rate.basisPoints(3000).shouldBeOk(), RoundingMode.DOWN).shouldBeErr().code shouldBe
                    "money_overflow"
            }
        }

        context("按分") {
            test("端数は剰余の大きい順、同順位は先頭から配る") {
                usd(100).allocateEvenly(3).shouldBeOk() shouldBe listOf(usd(34), usd(33), usd(33))
                jpy(1000).allocate(listOf(1, 1, 1)).shouldBeOk() shouldBe listOf(jpy(334), jpy(333), jpy(333))
                // 剰余: 10*1/6=1.66.., 10*2/6=3.33.., 10*3/6=5 → 剰余の大きい 1 番目に 1 単位
                jpy(10).allocate(listOf(1, 2, 3)).shouldBeOk() shouldBe listOf(jpy(2), jpy(3), jpy(5))
                jpy(-100).allocateEvenly(3).shouldBeOk() shouldBe listOf(jpy(-34), jpy(-33), jpy(-33))
                jpy(5).allocate(listOf(0, 1, 0, 1)).shouldBeOk() shouldBe listOf(jpy(0), jpy(3), jpy(0), jpy(2))
            }

            test("合計は必ず元の金額に一致し、各配分は正確な比例値との差が 1 単位未満") {
                checkAll(Arb.long(-1_000_000_000L..1_000_000_000L), Arb.list(Arb.long(0L..1_000_000L), 1..20)) { amount, weights ->
                    val total = weights.sum()
                    if (total == 0L) return@checkAll
                    val shares = jpy(amount).allocate(weights).shouldBeOk()
                    shares.sumOf { it.minorUnits } shouldBe amount
                    shares.zip(weights).forEach { (share, weight) ->
                        (share.minorUnits * total - amount * weight) shouldBeInRange -total..total
                        if (weight == 0L) share.minorUnits shouldBe 0L
                    }
                }
            }

            test("不正な重みは InvalidAllocation") {
                jpy(100).allocate(emptyList()).shouldBeErr().code shouldBe "invalid_allocation"
                jpy(100).allocate(listOf(1, -1)).shouldBeErr().code shouldBe "invalid_allocation"
                jpy(100).allocate(listOf(0, 0)).shouldBeErr().code shouldBe "invalid_allocation"
                jpy(100).allocateEvenly(0).shouldBeErr().code shouldBe "invalid_allocation"
                jpy(100).allocate(listOf(Long.MAX_VALUE, 1)).shouldBeErr().code shouldBe "money_overflow"
                jpy(Long.MAX_VALUE).allocate(listOf(2, 1)).shouldBeErr().code shouldBe "money_overflow"
                jpy(100).allocate(listOf(Long.MAX_VALUE)).shouldBeErr().code shouldBe "money_overflow"
            }

            test("全配分が同じ通貨") {
                usd(10).allocateEvenly(4).shouldBeOk().map { it.currency } shouldContainOnly listOf(Currency.USD)
            }
        }
    })
