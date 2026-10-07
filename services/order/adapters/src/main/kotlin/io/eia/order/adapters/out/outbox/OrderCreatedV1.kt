package io.eia.order.adapters.out.outbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// `sales.order.created.v1` のイベントの型(contracts/avro/sales/OrderCreated.avsc)。
// avro4k は型とスキーマを record のフルネームで対応させるため、@SerialName を契約の record のフルネームにする(ADR-0025 §1)。
// 項目は Canonical Model(io.eia.shared.canonical.sales.Order)と同じで、変換は OrderEventMapper で行う(ADR-0010 Decision 7)。

@Serializable
@SerialName("io.eia.events.sales.OrderCreated")
public data class OrderCreatedV1(
    val order: OrderV1,
)

@Serializable
@SerialName("io.eia.events.sales.Order")
public data class OrderV1(
    val id: String,
    val customerId: String,
    val status: OrderStatusV1,
    val orderedAt: Instant,
    val lines: List<OrderLineV1>,
    val totalAmount: MoneyV1,
    val shippingAddress: AddressV1,
)

@Serializable
@SerialName("io.eia.events.sales.OrderStatus")
public enum class OrderStatusV1 {
    PLACED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
}

@Serializable
@SerialName("io.eia.events.sales.OrderLine")
public data class OrderLineV1(
    val lineNumber: Int,
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: MoneyV1,
    val lineAmount: MoneyV1,
)

/** 金額(ADR-0012 §2)。[minorUnits] は最小通貨単位、[currency] は ISO 4217。 */
@Serializable
@SerialName("io.eia.events.common.Money")
public data class MoneyV1(
    val minorUnits: Long,
    val currency: String,
)

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
    // 住所は個人情報なので、ログに出さない
    override fun toString(): String = "AddressV1(countryCode=$countryCode, ***)"
}
