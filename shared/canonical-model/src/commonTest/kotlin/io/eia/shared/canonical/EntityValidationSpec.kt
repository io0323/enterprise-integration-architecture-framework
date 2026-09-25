package io.eia.shared.canonical

import io.eia.shared.canonical.Fixtures.T0
import io.eia.shared.canonical.Fixtures.T1
import io.eia.shared.canonical.Fixtures.address
import io.eia.shared.canonical.Fixtures.customer
import io.eia.shared.canonical.Fixtures.jpy
import io.eia.shared.canonical.Fixtures.order
import io.eia.shared.canonical.Fixtures.orderLine
import io.eia.shared.canonical.Fixtures.product
import io.eia.shared.canonical.Fixtures.shipment
import io.eia.shared.canonical.Fixtures.usd
import io.eia.shared.canonical.catalog.ProductId
import io.eia.shared.canonical.logistics.ShipmentId
import io.eia.shared.canonical.logistics.ShipmentStatus
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.canonical.sales.OrderId
import io.eia.shared.kernel.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private fun ValidationError.fields() = violations.map { it.field }

class EntityValidationSpec :
    FunSpec({
        test("正しいエンティティは自分自身を返す") {
            listOf(address, customer, product, order, shipment).forEach { it.validate().shouldBeOk() shouldBe it }
        }

        context("Order") {
            test("明細・合計・通貨・配送先の違反をすべて集めて返す") {
                val badLine = orderLine(1, 0, usd(100)).copy(lineAmount = usd(1), sku = " ", productId = ProductId(""))
                val jpyLine = orderLine(2, 1, jpy(100))
                val error =
                    order
                        .copy(
                            id = OrderId(""),
                            customerId = CustomerId(""),
                            lines = listOf(badLine, jpyLine),
                            shippingAddress = address.copy(countryCode = "JPN"),
                        ).validate()
                        .shouldBeErr()
                error.fields() shouldContainExactlyInAnyOrder
                    listOf(
                        "id",
                        "customerId",
                        "lines[0].productId",
                        "lines[0].sku",
                        "lines[0].quantity",
                        "lines[0].lineAmount",
                        "lines[1].lineAmount",
                        "shippingAddress.countryCode",
                        "totalAmount",
                    )
            }

            test("明細なし・lineNumber の重複・負の単価・単価 × 数量のオーバーフロー") {
                order
                    .copy(lines = emptyList())
                    .validate()
                    .shouldBeErr()
                    .fields() shouldBe listOf("lines", "totalAmount")
                order
                    .copy(lines = order.lines.map { it.copy(lineNumber = 1) })
                    .validate()
                    .shouldBeErr()
                    .fields() shouldBe listOf("lines")
                val negative = orderLine(1, 1, usd(-1))
                negative.validate().shouldBeErr().fields() shouldBe listOf("unitPrice")
                negative
                    .copy(lineNumber = 0, quantity = Long.MAX_VALUE, unitPrice = usd(2))
                    .validate()
                    .shouldBeErr()
                    .fields() shouldBe
                    listOf("lineNumber", "lineAmount")
            }
        }

        test("Customer と Address の違反") {
            customer
                .copy(
                    id = CustomerId(" "),
                    name = "",
                    email = "not-an-email",
                    updatedAt = T0,
                    createdAt = T1,
                    billingAddress = address.copy(postalCode = "", city = "", line1 = ""),
                ).validate()
                .shouldBeErr()
                .fields() shouldBe
                listOf("id", "name", "email", "updatedAt", "billingAddress.postalCode", "billingAddress.city", "billingAddress.line1")
            customer.copy(billingAddress = null).validate().shouldBeOk()
        }

        test("Product の違反") {
            product
                .copy(id = ProductId(""), sku = "", name = "", unitPrice = jpy(-1))
                .validate()
                .shouldBeErr()
                .fields() shouldBe
                listOf("id", "sku", "name", "unitPrice")
        }

        context("Shipment") {
            test("発送済み・配達済みの必須項目") {
                shipment
                    .copy(carrier = null, trackingNumber = " ", shippedAt = null, deliveredAt = null)
                    .validate()
                    .shouldBeErr()
                    .fields() shouldBe listOf("carrier", "trackingNumber", "shippedAt", "deliveredAt")
            }

            test("準備中なら運送会社や日時は不要") {
                shipment
                    .copy(status = ShipmentStatus.PREPARING, carrier = null, trackingNumber = null, shippedAt = null, deliveredAt = null)
                    .validate()
                    .shouldBeOk()
            }

            test("明細・日付の逆転・ID の違反") {
                shipment
                    .copy(
                        id = ShipmentId(""),
                        orderId = OrderId(""),
                        items = shipment.items.map { it.copy(lineNumber = 0, productId = ProductId(""), sku = "", quantity = 0) },
                        shippedAt = T1,
                        deliveredAt = T0,
                    ).validate()
                    .shouldBeErr()
                    .fields() shouldBe
                    listOf(
                        "id",
                        "orderId",
                        "items[0].lineNumber",
                        "items[0].productId",
                        "items[0].sku",
                        "items[0].quantity",
                        "deliveredAt",
                    )
                shipment
                    .copy(items = emptyList())
                    .validate()
                    .shouldBeErr()
                    .fields() shouldBe listOf("items")
            }
        }

        test("個人情報を含むエンティティは toString で伏せる") {
            customer.toString() shouldBe "Customer(id=c-001, ***)"
            address.toString() shouldBe "Address(countryCode=JP, ***)"
            order.toString() shouldNotContain "千代田"
            shipment.toString() shouldNotContain "千代田"
        }
    })
