package io.eia.b2bgateway.domain.order

/**
 * 社内の受注のシステム(order の API の `PlaceOrderRequest`。INT-SALES-001)に渡す注文(ADR-0040 §6)。
 * 業務の段階を通った注文([PurchaseOrder])と、受け付けの時点の対応表([CodeResolution])から作る。契約の JSON への変換は adapters が行う。
 *
 * @property idempotencyKey order の API の `Idempotency-Key`。`b2b:{取引先}:{交換の管理番号 0020}:{メッセージの参照番号 0062}`
 *   (同じ交換の同じメッセージなら、何度作っても同じ値)
 */
public data class OrderRequest(
    public val idempotencyKey: String,
    public val customerId: String,
    public val currency: String,
    public val lines: List<OrderRequestLine>,
    public val shippingAddress: ShippingAddress,
)

/** 明細。[unitPrice] は小数点を `.` にした 10 進の文字列(業務の段階で、通貨の小数桁数以内であることを確かめている)。 */
public data class OrderRequestLine(
    public val productId: String,
    public val sku: String,
    public val quantity: Long,
    public val unitPrice: String,
)

public object OrderRequests {
    /**
     * 業務の段階を通った注文から、order の要求を作る。届け先は NAD+DP があればその住所、なければ買い手の既定の届け先(仕様の ORD002)。
     * 対応表にないコードは業務の段階で拒否しているので、ここでは必ず引ける(引けなければプログラムの誤り)。
     */
    public fun from(
        key: SubmissionKey,
        order: PurchaseOrder,
        resolution: CodeResolution,
    ): OrderRequest {
        val customer = checkNotNull(resolution.buyers[order.buyer]) { "業務の段階を通った注文の買い手は、対応表にあります" }
        val address =
            order.deliveryParty?.let { checkNotNull(resolution.deliveryParties[it]) { "業務の段階を通った注文の届け先は、対応表にあります" } }
                ?: customer.defaultAddress
        return OrderRequest(
            idempotencyKey = key.value,
            customerId = customer.customerId,
            currency = order.currency,
            lines =
                order.lines.map { line ->
                    val item = checkNotNull(resolution.items[line.itemCode]) { "業務の段階を通った注文の商品は、対応表にあります" }
                    OrderRequestLine(item.productId, item.sku, line.quantity.toLong(), line.unitPrice)
                },
            shippingAddress = address,
        )
    }
}

/** 1 つの注文(受け取った交換の 1 メッセージ)を社内に取り込むときの冪等のキー(ADR-0040 §6)。 */
public data class SubmissionKey(
    public val partnerId: String,
    public val controlReference: String,
    public val messageReference: String,
) {
    public val value: String get() = "b2b:$partnerId:$controlReference:$messageReference"
}
