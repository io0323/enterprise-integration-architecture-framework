package io.eia.shared.canonical.billing

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.combine
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.MoneyError
import io.eia.shared.kernel.money.Rate
import io.eia.shared.kernel.money.RoundingMode
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** 消費税の端数処理を行う単位(ADR-0011 §4)。 */
@Serializable
public enum class TaxGranularity {
    /** 請求書ごと・税率ごとに 1 回丸める(インボイス制度に準拠。既定)。 */
    PER_INVOICE_PER_RATE,

    /** 明細ごとに丸めて税率ごとに合計する。インボイス制度の適格請求書では使えない(海外・レガシー互換用)。 */
    PER_LINE,
}

/**
 * 税額の計算規則。どの規則で計算したかを受信側が再計算して検証できるよう、Invoice に含めて運ぶ。
 * 丸め方は既定値を持たず、必ず明示する(ADR-0011 §3)。
 */
@Serializable
public data class TaxCalculationRule(
    val roundingMode: RoundingMode,
    val granularity: TaxGranularity = TaxGranularity.PER_INVOICE_PER_RATE,
)

/** 税率ごとの集計。[taxableAmount] は税抜の対象額、[taxAmount] は規則に従って丸めた税額。 */
@Serializable
public data class TaxSummary(
    @Contextual val taxRate: Rate,
    @Contextual val taxableAmount: Money,
    @Contextual val taxAmount: Money,
)

/** [TaxCalculationRule] に従って税率別の集計を計算する。 */
public object TaxCalculator {
    /**
     * 明細の税抜金額と税率から、税率別の集計を計算する。税率の並びは明細に初めて現れた順。
     * 通貨が [currency] と異なる明細があれば [MoneyError.CurrencyMismatch] を返す。
     */
    public fun summarize(
        lines: List<InvoiceLine>,
        rule: TaxCalculationRule,
        currency: Currency,
    ): Result<List<TaxSummary>, MoneyError> =
        lines
            .groupBy { it.taxRate }
            .map { (rate, linesOfRate) -> summarizeRate(rate, linesOfRate, rule, currency) }
            .combine()

    private fun summarizeRate(
        rate: Rate,
        lines: List<InvoiceLine>,
        rule: TaxCalculationRule,
        currency: Currency,
    ): Result<TaxSummary, MoneyError> =
        Money.sum(currency, lines.map { it.netAmount }).flatMap { taxable ->
            val tax =
                when (rule.granularity) {
                    TaxGranularity.PER_INVOICE_PER_RATE -> {
                        taxable.times(rate, rule.roundingMode)
                    }

                    TaxGranularity.PER_LINE -> {
                        lines
                            .map { it.netAmount.times(rate, rule.roundingMode) }
                            .combine()
                            .flatMap { Money.sum(currency, it) }
                    }
                }
            tax.map { TaxSummary(rate, taxable, it) }
        }
}
