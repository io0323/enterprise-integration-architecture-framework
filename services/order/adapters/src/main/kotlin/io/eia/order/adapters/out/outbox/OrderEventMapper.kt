package io.eia.order.adapters.out.outbox

import io.eia.order.domain.Order
import io.eia.order.domain.OrderLine
import io.eia.order.domain.OrderStatus
import io.eia.order.domain.ShippingAddress
import io.eia.shared.canonical.catalog.ProductId
import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.map
import io.eia.shared.kernel.money.Money
import io.eia.shared.canonical.sales.Order as CanonicalOrder
import io.eia.shared.canonical.sales.OrderId as CanonicalOrderId
import io.eia.shared.canonical.sales.OrderLine as CanonicalOrderLine
import io.eia.shared.canonical.sales.OrderStatus as CanonicalOrderStatus

/**
 * 注文(domain)を、Canonical Model を経由して `sales.order.created.v1` のイベントの型にする(Framework 8.3。ADR-0010 Decision 7)。
 *
 * Canonical Model の検証(`validate`。明細の金額・合計・通貨の一致・住所)を通したものだけをイベントにする。
 * domain の検証と同じ規則なので通常は失敗しないが、通らなければ発行せずに [ValidationError] を返す(業務の更新も取り消される)。
 */
public object OrderEventMapper {
    public fun orderCreated(order: Order): Result<OrderCreatedV1, ValidationError> =
        toCanonical(order).validate().map { OrderCreatedV1(it.toEvent()) }

    internal fun toCanonical(order: Order): CanonicalOrder =
        CanonicalOrder(
            id = CanonicalOrderId(order.id.value),
            customerId = CustomerId(order.customerId.value),
            status = order.status.toCanonical(),
            orderedAt = order.orderedAt,
            lines = order.lines.map { it.toCanonical() },
            totalAmount = order.totalAmount,
            shippingAddress = order.shippingAddress.toCanonical(),
        )

    private fun OrderStatus.toCanonical(): CanonicalOrderStatus =
        when (this) {
            OrderStatus.PLACED -> CanonicalOrderStatus.PLACED
            OrderStatus.CONFIRMED -> CanonicalOrderStatus.CONFIRMED
            OrderStatus.SHIPPED -> CanonicalOrderStatus.SHIPPED
            OrderStatus.DELIVERED -> CanonicalOrderStatus.DELIVERED
            OrderStatus.CANCELLED -> CanonicalOrderStatus.CANCELLED
        }

    private fun OrderLine.toCanonical(): CanonicalOrderLine =
        CanonicalOrderLine(lineNumber, ProductId(productId.value), sku.value, quantity, unitPrice, lineAmount)

    private fun ShippingAddress.toCanonical(): Address = Address(countryCode, postalCode, city, line1, region, line2)

    private fun CanonicalOrder.toEvent(): OrderV1 =
        OrderV1(
            id = id.value,
            customerId = customerId.value,
            status = OrderStatusV1.valueOf(status.name),
            orderedAt = orderedAt,
            lines =
                lines.map {
                    OrderLineV1(
                        it.lineNumber,
                        it.productId.value,
                        it.sku,
                        it.quantity,
                        it.unitPrice.toEvent(),
                        it.lineAmount.toEvent(),
                    )
                },
            totalAmount = totalAmount.toEvent(),
            shippingAddress = shippingAddress.let { AddressV1(it.countryCode, it.postalCode, it.city, it.line1, it.region, it.line2) },
        )

    private fun Money.toEvent(): MoneyV1 = MoneyV1(minorUnits, currency.code)
}
