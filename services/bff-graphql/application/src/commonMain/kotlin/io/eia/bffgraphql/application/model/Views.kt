package io.eia.bffgraphql.application.model

/** 画面に渡す注文。各サービスの契約の値をそのまま持ち、計算しない(ADR-0043 §8)。 */
public data class OrderView(
    val id: String,
    val customerId: String,
    val status: OrderStatusView,
    val orderedAt: String,
    val lines: List<OrderLineView>,
    val totalAmount: MoneyView,
    val shippingAddress: AddressView,
)

/** 注文の状態。一覧にない値は [UNKNOWN] にする(Tolerant Reader)。 */
public enum class OrderStatusView { PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, UNKNOWN }

public data class OrderLineView(
    val lineNumber: Int,
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: MoneyView,
    val lineAmount: MoneyView,
)

public data class MoneyView(
    val amount: String,
    val currency: String,
)

public data class AddressView(
    val countryCode: String,
    val postalCode: String,
    val region: String?,
    val city: String,
    val line1: String,
    val line2: String?,
)

/** SKU の在庫。[available] は在庫のサービスが計算した値をそのまま持つ。 */
public data class StockView(
    val sku: String,
    val available: Long,
    val updatedAt: String,
)

public data class ShipmentView(
    val orderId: String,
    val status: ShipmentStatusView,
    val shipmentId: String?,
    val shippedAt: String?,
    val destinationCountry: String?,
)

/** 出荷の状態。一覧にない値は [UNKNOWN] にする(Tolerant Reader)。 */
public enum class ShipmentStatusView { SHIPPED, REJECTED, UNKNOWN }
