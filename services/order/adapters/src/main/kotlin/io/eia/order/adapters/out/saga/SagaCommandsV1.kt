package io.eia.order.adapters.out.saga

import io.eia.order.adapters.out.outbox.AddressV1
import io.eia.order.adapters.out.outbox.MoneyV1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 注文 Saga のコマンドの型(contracts/avro/{inventory,payment,shipping}。INT-*-001。ADR-0029 §2)。
// @SerialName は契約の record のフルネーム(ADR-0025 §1)。金額と住所は注文のイベントと同じ型(io.eia.events.common)。

@Serializable
@SerialName("io.eia.events.inventory.StockLine")
public data class StockLineV1(
    val lineNumber: Int,
    val sku: String,
    val quantity: Long,
)

@Serializable
@SerialName("io.eia.events.inventory.ReserveStock")
public data class ReserveStockV1(
    val sagaId: String,
    val orderId: String,
    val lines: List<StockLineV1>,
)

@Serializable
@SerialName("io.eia.events.inventory.ReleaseStock")
public data class ReleaseStockV1(
    val sagaId: String,
    val orderId: String,
)

@Serializable
@SerialName("io.eia.events.payment.AuthorizePayment")
public data class AuthorizePaymentV1(
    val sagaId: String,
    val orderId: String,
    val customerId: String,
    val amount: MoneyV1,
) {
    // 顧客 ID と金額はログに出さない
    override fun toString(): String = "AuthorizePaymentV1(sagaId=$sagaId, orderId=$orderId, ***)"
}

@Serializable
@SerialName("io.eia.events.payment.VoidPayment")
public data class VoidPaymentV1(
    val sagaId: String,
    val orderId: String,
)

@Serializable
@SerialName("io.eia.events.shipping.ShipmentLine")
public data class ShipmentLineV1(
    val lineNumber: Int,
    val sku: String,
    val quantity: Long,
)

@Serializable
@SerialName("io.eia.events.shipping.ArrangeShipment")
public data class ArrangeShipmentV1(
    val sagaId: String,
    val orderId: String,
    val shippingAddress: AddressV1,
    val lines: List<ShipmentLineV1>,
)

@Serializable
@SerialName("io.eia.events.shipping.CancelShipment")
public data class CancelShipmentV1(
    val sagaId: String,
    val orderId: String,
)
