package io.eia.batchetl.adapters.kafka

import io.eia.batchetl.domain.OrderEvent
import io.eia.batchetl.domain.OrderStatus
import io.eia.batchetl.domain.ShippingAddress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// 取り込む注文のイベントの型(contracts/avro/sales の OrderCreated・OrderCancelled・OrderStatusChanged。INT-SALES-002)。
// avro4k は型とスキーマを record のフルネームで対応させるため、@SerialName を契約の record のフルネームにする(ADR-0025 §1)。
// 受信側の型は受信側が持つ(発行側の order の型に依存しない)。

@Serializable
@SerialName("io.eia.events.sales.OrderCreated")
internal data class OrderCreatedV1(
    val order: OrderV1,
)

@Serializable
@SerialName("io.eia.events.sales.Order")
internal data class OrderV1(
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
internal enum class OrderStatusV1 {
    PLACED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
}

@Serializable
@SerialName("io.eia.events.sales.OrderLine")
internal data class OrderLineV1(
    val lineNumber: Int,
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: MoneyV1,
    val lineAmount: MoneyV1,
)

@Serializable
@SerialName("io.eia.events.common.Money")
internal data class MoneyV1(
    val minorUnits: Long,
    val currency: String,
)

@Serializable
@SerialName("io.eia.events.common.Address")
internal data class AddressV1(
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

@Serializable
@SerialName("io.eia.events.sales.OrderCancelled")
internal data class OrderCancelledV1(
    val orderId: String,
    val cancelledAt: Instant,
    val reason: String,
)

@Serializable
@SerialName("io.eia.events.sales.OrderStatusChanged")
internal data class OrderStatusChangedV1(
    val orderId: String,
    val status: OrderStatusV1,
    val previousStatus: OrderStatusV1,
    val version: Long,
    val changedAt: Instant,
)

internal fun OrderCreatedV1.toDomain(): OrderEvent.Placed =
    OrderEvent.Placed(
        orderId = order.id,
        customerId = order.customerId,
        orderedAt = order.orderedAt,
        totalMinorUnits = order.totalAmount.minorUnits,
        currency = order.totalAmount.currency,
        lineCount = order.lines.size,
        shippingAddress =
            with(order.shippingAddress) {
                ShippingAddress(countryCode, postalCode, region, city, line1, line2)
            },
    )

internal fun OrderCancelledV1.toDomain(): OrderEvent.Cancelled = OrderEvent.Cancelled(orderId, cancelledAt, reason)

internal fun OrderStatusChangedV1.toDomain(): OrderEvent.StatusChanged =
    OrderEvent.StatusChanged(orderId, OrderStatus.valueOf(status.name), version, changedAt)
