package io.eia.order.domain

import io.eia.shared.kernel.money.Money

/**
 * 注文明細。金額はすべて税抜で、[lineAmount] = [unitPrice] × [quantity](ADR-0011 §4)。
 * 作るのは [Order.place] だけ(明細番号・金額の整合を集約で保証するため)。
 */
public class OrderLine internal constructor(
    /** 1 から始まる連番。 */
    public val lineNumber: Int,
    public val productId: ProductId,
    public val sku: Sku,
    public val quantity: Long,
    public val unitPrice: Money,
    public val lineAmount: Money,
) {
    override fun equals(other: Any?): Boolean =
        other is OrderLine &&
            lineNumber == other.lineNumber &&
            productId == other.productId &&
            sku == other.sku &&
            quantity == other.quantity &&
            unitPrice == other.unitPrice &&
            lineAmount == other.lineAmount

    override fun hashCode(): Int = listOf(lineNumber, productId, sku, quantity, unitPrice, lineAmount).hashCode()

    override fun toString(): String = "OrderLine(lineNumber=$lineNumber, sku=$sku, quantity=$quantity, lineAmount=$lineAmount)"
}

/** 受け付ける明細(検証前)。項目の名前は契約の `PlaceOrderLine` と同じ。 */
public data class OrderLineDraft(
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: Money,
)
