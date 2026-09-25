package io.eia.shared.canonical.sales

import io.eia.shared.canonical.catalog.ProductId
import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.common.Validatable
import io.eia.shared.canonical.common.checkCurrency
import io.eia.shared.canonical.common.checkEquals
import io.eia.shared.canonical.common.checkNotNegative
import io.eia.shared.canonical.common.validating
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Money
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
@JvmInline
public value class OrderId(
    public val value: String,
)

@Serializable
public enum class OrderStatus {
    PLACED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
}

/** 注文明細。金額はすべて税抜で、[lineAmount] = [unitPrice] × [quantity](ADR-0011 §4)。 */
@Serializable
public data class OrderLine(
    val lineNumber: Int,
    val productId: ProductId,
    val sku: String,
    val quantity: Long,
    @Contextual val unitPrice: Money,
    @Contextual val lineAmount: Money,
) : Validatable<OrderLine> {
    override fun validate(): Result<OrderLine, ValidationError> =
        validating(this) {
            check(lineNumber >= 1, "lineNumber") { "1 以上です" }
            check(productId.value.isNotBlank(), "productId") { "必須です" }
            check(sku.isNotBlank(), "sku") { "必須です" }
            check(quantity >= 1, "quantity") { "1 以上です" }
            checkNotNegative("unitPrice", unitPrice)
            checkEquals("lineAmount", lineAmount, unitPrice * quantity, "単価 × 数量")
        }
}

/** 注文。[totalAmount] は明細金額(税抜)の合計で、全明細の通貨は [totalAmount] の通貨と一致する。 */
@Serializable
public data class Order(
    val id: OrderId,
    val customerId: CustomerId,
    val status: OrderStatus,
    val orderedAt: Instant,
    val lines: List<OrderLine>,
    @Contextual val totalAmount: Money,
    val shippingAddress: Address,
) : Validatable<Order> {
    override fun validate(): Result<Order, ValidationError> =
        validating(this) {
            check(id.value.isNotBlank(), "id") { "必須です" }
            check(customerId.value.isNotBlank(), "customerId") { "必須です" }
            check(lines.isNotEmpty(), "lines") { "明細が 1 件以上必要です" }
            check(lines.map { it.lineNumber }.toSet().size == lines.size, "lines") { "lineNumber が重複しています" }
            lines.forEachIndexed { i, line ->
                include("lines[$i]", line.validate())
                checkCurrency("lines[$i].lineAmount", line.lineAmount, totalAmount.currency)
            }
            include("shippingAddress", shippingAddress.validate())
            checkEquals("totalAmount", totalAmount, Money.sum(totalAmount.currency, lines.map { it.lineAmount }), "明細金額の合計")
        }
}
