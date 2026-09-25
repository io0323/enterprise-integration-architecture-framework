package io.eia.shared.canonical

import io.eia.shared.canonical.Fixtures.T0
import io.eia.shared.canonical.Fixtures.T1
import io.eia.shared.canonical.Fixtures.invoiceLine
import io.eia.shared.canonical.Fixtures.jpy
import io.eia.shared.canonical.Fixtures.rate
import io.eia.shared.canonical.Fixtures.usd
import io.eia.shared.canonical.billing.InvoiceDraft
import io.eia.shared.canonical.billing.InvoiceId
import io.eia.shared.canonical.billing.InvoiceLine
import io.eia.shared.canonical.billing.TaxCalculationRule
import io.eia.shared.canonical.billing.TaxCalculator
import io.eia.shared.canonical.billing.TaxGranularity
import io.eia.shared.canonical.billing.TaxSummary
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.canonical.sales.OrderId
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.RoundingMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

private val perInvoice = TaxCalculationRule(RoundingMode.DOWN)
private val perLine = TaxCalculationRule(RoundingMode.DOWN, TaxGranularity.PER_LINE)

private fun issue(
    lines: List<InvoiceLine>,
    rule: TaxCalculationRule,
    currency: Currency = Currency.JPY,
) = InvoiceDraft(InvoiceId("i-001"), OrderId("o-001"), CustomerId("c-001"), T0, T1, rule, lines, currency).issue()

class InvoiceSpec :
    FunSpec({
        // 105 円 × 3 明細、軽減税率 8%: 明細ごとに丸めると 8.4 → 8 円 × 3 = 24 円、請求書ごとに丸めると 315 × 8% = 25.2 → 25 円
        val reducedRateLines = (1..3).map { invoiceLine(it, 1, jpy(105), rate(800)) }

        test("既定の規則は請求書ごと・税率ごとに 1 回丸める(インボイス制度)") {
            TaxCalculationRule(RoundingMode.DOWN).granularity shouldBe TaxGranularity.PER_INVOICE_PER_RATE
            val invoice = issue(reducedRateLines, perInvoice).shouldBeOk()
            invoice.taxSummaries shouldBe listOf(TaxSummary(rate(800), jpy(315), jpy(25)))
            invoice.subtotal shouldBe jpy(315)
            invoice.taxTotal shouldBe jpy(25)
            invoice.total shouldBe jpy(340)
        }

        test("明細ごとに丸める規則に切り替えられる") {
            val invoice = issue(reducedRateLines, perLine).shouldBeOk()
            invoice.taxSummaries shouldBe listOf(TaxSummary(rate(800), jpy(315), jpy(24)))
            invoice.total shouldBe jpy(339)
        }

        test("丸め方は規則の指定に従う") {
            issue(reducedRateLines, TaxCalculationRule(RoundingMode.HALF_UP)).shouldBeOk().taxTotal shouldBe jpy(25)
            issue(reducedRateLines, TaxCalculationRule(RoundingMode.UP)).shouldBeOk().taxTotal shouldBe jpy(26)
        }

        test("税率が混在する請求書は税率ごとに集計する") {
            val lines =
                listOf(
                    invoiceLine(1, 3, jpy(398), rate(800)),
                    invoiceLine(2, 1, jpy(1980), rate(1000)),
                    invoiceLine(3, 2, jpy(129), rate(800)),
                )
            val invoice = issue(lines, perInvoice).shouldBeOk()
            // 8%: (1194 + 258) × 8% = 116.16 → 116、10%: 1980 × 10% = 198
            invoice.taxSummaries shouldContainExactlyInAnyOrder
                listOf(TaxSummary(rate(800), jpy(1452), jpy(116)), TaxSummary(rate(1000), jpy(1980), jpy(198)))
            invoice.total shouldBe jpy(3746)
        }

        test("USD でも最小通貨単位で丸める") {
            val invoice = issue(listOf(invoiceLine(1, 1, usd(999), rate(725))), TaxCalculationRule(RoundingMode.HALF_EVEN), Currency.USD)
            // 9.99 × 7.25% = 0.724275 → 0.72
            invoice.shouldBeOk().taxTotal shouldBe usd(72)
        }

        context("validate は規則に沿った再計算で検証する") {
            val valid = issue(reducedRateLines, perInvoice).shouldBeOk()

            test("明細ごとの丸めで計算した税額は、請求書ごとの規則では不一致になる") {
                val error =
                    valid
                        .copy(
                            taxSummaries = listOf(TaxSummary(rate(800), jpy(315), jpy(24))),
                            taxTotal = jpy(24),
                            total = jpy(339),
                        ).validate()
                        .shouldBeErr()
                error.violations.map { it.field } shouldBe listOf("taxSummaries")
            }

            test("同じ値でも規則を明細ごとにすれば一致する") {
                valid
                    .copy(
                        taxRule = perLine,
                        taxSummaries = listOf(TaxSummary(rate(800), jpy(315), jpy(24))),
                        taxTotal = jpy(24),
                        total = jpy(339),
                    ).validate()
                    .shouldBeOk()
            }

            test("小計・税額合計・総額の不一致と税率の重複を検出する") {
                val summary = valid.taxSummaries.single()
                val error =
                    valid
                        .copy(subtotal = jpy(300), taxTotal = jpy(26), total = jpy(999), taxSummaries = listOf(summary, summary))
                        .validate()
                        .shouldBeErr()
                error.violations.map { it.field } shouldContainExactlyInAnyOrder
                    listOf("subtotal", "taxSummaries", "taxTotal", "total")
            }

            test("明細の不整合・通貨の混在・日付の逆転を検出する") {
                val badLine = reducedRateLines[0].copy(netAmount = jpy(1), quantity = 0)
                val usdLine = invoiceLine(2, 1, usd(105), rate(800))
                val error = valid.copy(lines = listOf(badLine, usdLine), dueAt = T0 - 1.days).validate().shouldBeErr()
                error.violations.map { it.field } shouldContainExactlyInAnyOrder
                    listOf(
                        "dueAt",
                        "lines[0].quantity",
                        "lines[0].netAmount",
                        "lines[1].netAmount",
                        "subtotal",
                        "taxSummaries",
                    )
            }

            test("明細なし・lineNumber 重複・空の ID を検出する") {
                valid
                    .copy(lines = emptyList(), taxSummaries = emptyList())
                    .validate()
                    .shouldBeErr()
                    .violations
                    .map { it.field } shouldBe
                    listOf("lines", "subtotal", "taxTotal")
                valid
                    .copy(id = InvoiceId(""), lines = reducedRateLines.map { it.copy(lineNumber = 1) })
                    .validate()
                    .shouldBeErr()
                    .violations
                    .map { it.field } shouldBe listOf("id", "lines")
            }
        }

        test("通貨の異なる明細は集計できない") {
            TaxCalculator.summarize(listOf(invoiceLine(1, 1, usd(100), rate(1000))), perInvoice, Currency.JPY).shouldBeErr().code shouldBe
                "currency_mismatch"
            issue(listOf(invoiceLine(1, 1, usd(100), rate(1000))), perLine).shouldBeErr().code shouldBe "currency_mismatch"
        }
    })
