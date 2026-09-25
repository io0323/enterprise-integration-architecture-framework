package io.eia.shared.canonical.billing

import io.eia.shared.canonical.catalog.ProductId
import io.eia.shared.canonical.common.Validatable
import io.eia.shared.canonical.common.Violations
import io.eia.shared.canonical.common.checkCurrency
import io.eia.shared.canonical.common.checkEquals
import io.eia.shared.canonical.common.checkNotNegative
import io.eia.shared.canonical.common.validating
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.canonical.sales.OrderId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.Rate
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
@JvmInline
public value class InvoiceId(
    public val value: String,
)

@Serializable
public enum class InvoiceStatus {
    ISSUED,
    PAID,
    VOID,
}

/** 請求明細。金額は税抜で、[netAmount] = [unitPrice] × [quantity]。税額は明細には持たず [TaxSummary] で集計する。 */
@Serializable
public data class InvoiceLine(
    val lineNumber: Int,
    val productId: ProductId,
    val description: String,
    val quantity: Long,
    @Contextual val unitPrice: Money,
    @Contextual val netAmount: Money,
    @Contextual val taxRate: Rate,
) : Validatable<InvoiceLine> {
    override fun validate(): Result<InvoiceLine, ValidationError> =
        validating(this) {
            check(lineNumber >= 1, "lineNumber") { "1 以上です" }
            check(productId.value.isNotBlank(), "productId") { "必須です" }
            check(description.isNotBlank(), "description") { "必須です" }
            check(quantity >= 1, "quantity") { "1 以上です" }
            checkNotNegative("unitPrice", unitPrice)
            checkEquals("netAmount", netAmount, unitPrice * quantity, "単価 × 数量")
        }
}

/**
 * 請求書。税額は [taxRule] に従って計算する(ADR-0011 §4)。
 *
 * - [taxSummaries]: 税率ごとの対象額と税額。[TaxCalculator] の計算結果と一致すること(順序は問わない)
 * - [subtotal] = 明細の税抜金額の合計、[taxTotal] = 税率別の税額の合計、[total] = [subtotal] + [taxTotal]
 *
 * 発行側は [InvoiceDraft.issue] で集計値を計算して作る。受信側は `CanonicalCodec.decode` で同じ計算による検証を受ける。
 */
@Serializable
public data class Invoice(
    val id: InvoiceId,
    val orderId: OrderId,
    val customerId: CustomerId,
    val status: InvoiceStatus,
    val issuedAt: Instant,
    val dueAt: Instant,
    val taxRule: TaxCalculationRule,
    val lines: List<InvoiceLine>,
    val taxSummaries: List<TaxSummary>,
    @Contextual val subtotal: Money,
    @Contextual val taxTotal: Money,
    @Contextual val total: Money,
) : Validatable<Invoice> {
    override fun validate(): Result<Invoice, ValidationError> =
        validating(this) {
            check(id.value.isNotBlank(), "id") { "必須です" }
            check(orderId.value.isNotBlank(), "orderId") { "必須です" }
            check(customerId.value.isNotBlank(), "customerId") { "必須です" }
            check(dueAt >= issuedAt, "dueAt") { "issuedAt 以降です" }
            check(lines.isNotEmpty(), "lines") { "明細が 1 件以上必要です" }
            check(lines.map { it.lineNumber }.toSet().size == lines.size, "lines") { "lineNumber が重複しています" }
            val currency = subtotal.currency
            lines.forEachIndexed { i, line ->
                include("lines[$i]", line.validate())
                checkCurrency("lines[$i].netAmount", line.netAmount, currency)
            }
            checkTotals(currency)
        }

    private fun Violations.checkTotals(currency: Currency) {
        checkEquals("subtotal", subtotal, Money.sum(currency, lines.map { it.netAmount }), "明細の税抜金額の合計")
        check(taxSummaries.map { it.taxRate }.toSet().size == taxSummaries.size, "taxSummaries") { "税率が重複しています" }
        when (val expected = TaxCalculator.summarize(lines, taxRule, currency)) {
            is Result.Ok -> {
                check(taxSummaries.toSet() == expected.value.toSet(), "taxSummaries") { "税率別の集計が ${taxRule.granularity} の計算結果と一致しません" }
            }

            is Result.Err -> {
                check(false, "taxSummaries") { "税率別の集計を計算できません(${expected.error.code})" }
            }
        }
        checkEquals("taxTotal", taxTotal, Money.sum(currency, taxSummaries.map { it.taxAmount }), "税率別の税額の合計")
        checkEquals("total", total, subtotal + taxTotal, "小計 + 税額")
    }
}

/** 集計値を含まない請求書の下書き。[issue] で税率別の集計・小計・税額・総額を [taxRule] に従って計算し、検証済みの [Invoice] にする。 */
public data class InvoiceDraft(
    val id: InvoiceId,
    val orderId: OrderId,
    val customerId: CustomerId,
    val issuedAt: Instant,
    val dueAt: Instant,
    val taxRule: TaxCalculationRule,
    val lines: List<InvoiceLine>,
    val currency: Currency,
) {
    public fun issue(): Result<Invoice, DomainError> =
        TaxCalculator.summarize(lines, taxRule, currency).flatMap { summaries ->
            Money.sum(currency, lines.map { it.netAmount }).flatMap { subtotal ->
                Money.sum(currency, summaries.map { it.taxAmount }).flatMap { taxTotal ->
                    (subtotal + taxTotal).flatMap { total ->
                        Invoice(
                            id = id,
                            orderId = orderId,
                            customerId = customerId,
                            status = InvoiceStatus.ISSUED,
                            issuedAt = issuedAt,
                            dueAt = dueAt,
                            taxRule = taxRule,
                            lines = lines,
                            taxSummaries = summaries,
                            subtotal = subtotal,
                            taxTotal = taxTotal,
                            total = total,
                        ).validate()
                    }
                }
            }
        }
}
