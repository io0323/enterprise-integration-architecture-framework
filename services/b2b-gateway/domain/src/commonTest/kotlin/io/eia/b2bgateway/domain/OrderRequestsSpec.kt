package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.InternalCustomer
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.OrderLine
import io.eia.b2bgateway.domain.order.OrderRequest
import io.eia.b2bgateway.domain.order.OrderRequestLine
import io.eia.b2bgateway.domain.order.OrderRequests
import io.eia.b2bgateway.domain.order.PurchaseOrder
import io.eia.b2bgateway.domain.order.ShippingAddress
import io.eia.b2bgateway.domain.order.SubmissionKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val DEFAULT_ADDRESS = ShippingAddress("JP", "100-0001", "Tokyo", "Chiyoda", "1-1", null)
private val DELIVERY_ADDRESS = ShippingAddress("JP", "530-0001", "Osaka", "Kita", "2-2", "Bldg 3")
private val KEY = SubmissionKey("partner01", "ICR1001", "2")

private val RESOLUTION =
    CodeResolution(
        buyers = mapOf("4912345000019" to InternalCustomer("cust-1", DEFAULT_ADDRESS)),
        deliveryParties = mapOf("4912345000026" to DELIVERY_ADDRESS),
        items =
            mapOf(
                ItemCode.BuyerPartNumber("ABC-123") to InternalItem("prod-1", "SKU-1"),
                ItemCode.Gtin("4901234567900") to InternalItem("prod-2", "SKU-2"),
            ),
    )

private fun order(deliveryParty: String?) =
    PurchaseOrder(
        orderNumber = "PO-1",
        documentDate = "20261030",
        requestedDelivery = null,
        buyer = "4912345000019",
        supplier = "4900000000001",
        deliveryParty = deliveryParty,
        currency = "JPY",
        lines = listOf(OrderLine("1", "4901234567894", "ABC-123", "10", "1200"), OrderLine("2", "4901234567900", null, "5", "980.5")),
        declaredLineCount = "2",
    )

class OrderRequestsSpec :
    FunSpec({
        test("社内の顧客・商品に直し、NAD+DP があればその住所に届ける。冪等のキーは取引先・交換の管理番号・メッセージの参照番号") {
            OrderRequests.from(KEY, order("4912345000026"), RESOLUTION) shouldBe
                OrderRequest(
                    idempotencyKey = "b2b:partner01:ICR1001:2",
                    customerId = "cust-1",
                    currency = "JPY",
                    lines = listOf(OrderRequestLine("prod-1", "SKU-1", 10, "1200"), OrderRequestLine("prod-2", "SKU-2", 5, "980.5")),
                    shippingAddress = DELIVERY_ADDRESS,
                )
        }

        test("NAD+DP がなければ、買い手の既定の届け先に届ける(仕様の ORD002)") {
            OrderRequests.from(KEY, order(null), RESOLUTION).shippingAddress shouldBe DEFAULT_ADDRESS
        }
    })
