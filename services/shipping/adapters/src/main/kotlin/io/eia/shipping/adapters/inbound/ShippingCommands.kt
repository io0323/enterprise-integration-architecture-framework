package io.eia.shipping.adapters.inbound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// コマンドの型(contracts/avro/shipping。INT-SHIPPING-001)。@SerialName は契約の record のフルネーム(ADR-0025 §1)。

@Serializable
@SerialName("io.eia.events.common.Address")
public data class AddressV1(
    val countryCode: String,
    val postalCode: String,
    val city: String,
    val line1: String,
    val region: String? = null,
    val line2: String? = null,
) {
    // 住所はログに出さない(個人情報。CLAUDE.md §5 可観測性)
    override fun toString(): String = "AddressV1(countryCode=$countryCode, ***)"
}

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
