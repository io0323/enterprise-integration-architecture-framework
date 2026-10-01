package io.eia.order.adapters.inbound.rest

import kotlinx.serialization.Serializable

// 契約(contracts/openapi/order-api.v1.yaml)の DTO。domain のモデルとは別にし、変換は OrderDtoMapper で行う(CLAUDE.md §4)。

/** `Money`。amount は通貨の小数桁数で表した 10 進の文字列(ADR-0011 §1)。 */
@Serializable
internal data class MoneyDto(
    val amount: String,
    val currency: String,
)

@Serializable
internal data class AddressDto(
    val countryCode: String,
    val postalCode: String,
    val region: String? = null,
    val city: String,
    val line1: String,
    val line2: String? = null,
)

@Serializable
internal data class PlaceOrderLineDto(
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: MoneyDto,
)

/** `PlaceOrderRequest`。 */
@Serializable
internal data class PlaceOrderRequestDto(
    val customerId: String,
    val lines: List<PlaceOrderLineDto>,
    val shippingAddress: AddressDto,
)

@Serializable
internal data class OrderLineDto(
    val lineNumber: Int,
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: MoneyDto,
    val lineAmount: MoneyDto,
)

/** `Order`。 */
@Serializable
internal data class OrderDto(
    val id: String,
    val customerId: String,
    val status: String,
    val orderedAt: String,
    val lines: List<OrderLineDto>,
    val totalAmount: MoneyDto,
    val shippingAddress: AddressDto,
)
