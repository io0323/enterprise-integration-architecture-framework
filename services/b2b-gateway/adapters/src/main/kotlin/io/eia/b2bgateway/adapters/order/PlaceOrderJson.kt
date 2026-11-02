package io.eia.b2bgateway.adapters.order

import io.eia.b2bgateway.domain.order.OrderRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * order の API の要求の本文(契約 `contracts/openapi/order-api.v1.yaml` の `PlaceOrderRequest`。INT-SALES-001)。
 * 受け付けの時点で 1 回だけ作り、送信待ちに固定する(送り直しでは同じバイト列を送る。ADR-0040 §6)。
 */
public object PlaceOrderJson {
    private val json = Json { explicitNulls = true }

    public fun encode(request: OrderRequest): ByteArray =
        json
            .encodeToString(
                Body.serializer(),
                Body(
                    customerId = request.customerId,
                    lines =
                        request.lines.map {
                            Line(it.productId, it.sku, it.quantity, Money(it.unitPrice, request.currency))
                        },
                    shippingAddress =
                        request.shippingAddress.let {
                            Address(it.countryCode, it.postalCode, it.region, it.city, it.line1, it.line2)
                        },
                ),
            ).encodeToByteArray()

    @Serializable
    private class Body(
        val customerId: String,
        val lines: List<Line>,
        val shippingAddress: Address,
    )

    @Serializable
    private class Line(
        val productId: String,
        val sku: String,
        val quantity: Long,
        val unitPrice: Money,
    )

    @Serializable
    private class Money(
        val amount: String,
        val currency: String,
    )

    @Serializable
    private class Address(
        val countryCode: String,
        val postalCode: String,
        val region: String?,
        val city: String,
        val line1: String,
        val line2: String?,
    )
}
