package io.eia.shared.canonical

import io.eia.shared.canonical.billing.InvoiceLine
import io.eia.shared.canonical.catalog.Product
import io.eia.shared.canonical.catalog.ProductId
import io.eia.shared.canonical.catalog.ProductStatus
import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.logistics.Shipment
import io.eia.shared.canonical.logistics.ShipmentId
import io.eia.shared.canonical.logistics.ShipmentItem
import io.eia.shared.canonical.logistics.ShipmentStatus
import io.eia.shared.canonical.sales.Customer
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.canonical.sales.Order
import io.eia.shared.canonical.sales.OrderId
import io.eia.shared.canonical.sales.OrderLine
import io.eia.shared.canonical.sales.OrderStatus
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.Rate
import io.kotest.assertions.fail
import kotlin.time.Instant

fun <T> Result<T, *>.shouldBeOk(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

fun <E> Result<*, E>.shouldBeErr(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

object Fixtures {
    val T0: Instant = Instant.parse("2026-09-25T01:00:00Z")
    val T1: Instant = Instant.parse("2026-09-26T01:00:00Z")

    fun jpy(amount: Long): Money = Money.ofMinor(amount, Currency.JPY)

    fun usd(cents: Long): Money = Money.ofMinor(cents, Currency.USD)

    fun rate(basisPoints: Long): Rate = Rate.basisPoints(basisPoints).shouldBeOk()

    val address = Address(countryCode = "JP", postalCode = "100-0001", city = "千代田区", line1 = "千代田1-1")

    val customer =
        Customer(
            id = CustomerId("c-001"),
            name = "山田 太郎",
            email = "taro@example.com",
            createdAt = T0,
            updatedAt = T1,
            billingAddress = address,
        )

    val product =
        Product(
            id = ProductId("p-001"),
            sku = "SKU-001",
            name = "ノート",
            unitPrice = jpy(150),
            status = ProductStatus.ACTIVE,
            updatedAt = T0,
        )

    fun orderLine(
        lineNumber: Int,
        quantity: Long,
        unitPrice: Money,
    ): OrderLine =
        OrderLine(
            lineNumber = lineNumber,
            productId = ProductId("p-00$lineNumber"),
            sku = "SKU-00$lineNumber",
            quantity = quantity,
            unitPrice = unitPrice,
            lineAmount = (unitPrice * quantity).shouldBeOk(),
        )

    val order =
        Order(
            id = OrderId("o-001"),
            customerId = CustomerId("c-001"),
            status = OrderStatus.PLACED,
            orderedAt = T0,
            lines = listOf(orderLine(1, 2, usd(1250)), orderLine(2, 1, usd(99))),
            totalAmount = usd(2599),
            shippingAddress = address,
        )

    fun invoiceLine(
        lineNumber: Int,
        quantity: Long,
        unitPrice: Money,
        taxRate: Rate,
    ): InvoiceLine =
        InvoiceLine(
            lineNumber = lineNumber,
            productId = ProductId("p-00$lineNumber"),
            description = "商品 $lineNumber",
            quantity = quantity,
            unitPrice = unitPrice,
            netAmount = (unitPrice * quantity).shouldBeOk(),
            taxRate = taxRate,
        )

    val shipment =
        Shipment(
            id = ShipmentId("s-001"),
            orderId = OrderId("o-001"),
            status = ShipmentStatus.DELIVERED,
            destination = address,
            items = listOf(ShipmentItem(1, ProductId("p-001"), "SKU-001", 2)),
            carrier = "yamato",
            trackingNumber = "1234-5678",
            shippedAt = T0,
            deliveredAt = T1,
        )
}
