package io.eia.bffgraphql.application

import io.eia.bffgraphql.application.model.AddressView
import io.eia.bffgraphql.application.model.MoneyView
import io.eia.bffgraphql.application.model.OrderLineView
import io.eia.bffgraphql.application.model.OrderStatusView
import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentStatusView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.bffgraphql.application.port.outbound.OrderQueries
import io.eia.bffgraphql.application.port.outbound.ShipmentQueries
import io.eia.bffgraphql.application.port.outbound.StockQueries
import io.eia.bffgraphql.application.usecase.LoadOrdersService
import io.eia.bffgraphql.application.usecase.LoadShipmentsService
import io.eia.bffgraphql.application.usecase.LoadStockLevelsService
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun stock(sku: String) = StockView(sku, 1, "2026-11-03T00:00:00Z")

private class FakeStock(
    private val failOnCall: Int? = null,
) : StockQueries {
    val calls = mutableListOf<Set<String>>()

    override suspend fun find(skus: Set<String>): Result<List<StockView>, DomainError> {
        calls += skus
        return if (calls.size == failOnCall) err(UnavailableError("inventory")) else ok(skus.map(::stock))
    }
}

class LoadServicesSpec :
    FunSpec({
        test("在庫: 上限ごとに分けて読み、まとめて返す") {
            val queries = FakeStock()

            val levels = LoadStockLevelsService(queries, chunkSize = 2)(setOf("a", "b", "c"))

            queries.calls.map { it.size } shouldBe listOf(2, 1)
            levels shouldBe ok(mapOf("a" to stock("a"), "b" to stock("b"), "c" to stock("c")))
        }

        test("在庫: 1 つでも読めなければ失敗にする(一部を「ない」にしない)") {
            val levels = LoadStockLevelsService(FakeStock(failOnCall = 2), chunkSize = 2)(setOf("a", "b", "c"))

            levels shouldBe err(UnavailableError("inventory"))
        }

        test("注文: ID で引けるようにする。読めなければ失敗を返す") {
            val money = MoneyView("10.00", "USD")
            val order =
                OrderView(
                    "order-1",
                    "customer-1",
                    OrderStatusView.PLACED,
                    "2026-11-03T00:00:00Z",
                    listOf(OrderLineView(1, "product-1", "SKU-1", 2, money, money)),
                    money,
                    AddressView("JP", "100-0001", null, "Tokyo", "1-1", null),
                )

            fun queries(result: Result<List<OrderView>, DomainError>) =
                object : OrderQueries {
                    override suspend fun find(ids: Set<String>) = result
                }

            LoadOrdersService(queries(ok(listOf(order))))(setOf("order-1")) shouldBe ok(mapOf("order-1" to order))
            LoadOrdersService(queries(err(UnavailableError("order"))))(setOf("order-1")) shouldBe err(UnavailableError("order"))
        }

        test("出荷: 注文の ID で引けるようにする") {
            val shipment = ShipmentView("order-1", ShipmentStatusView.SHIPPED, "s", "t", "JP")
            val queries =
                object : ShipmentQueries {
                    override suspend fun find(orderIds: Set<String>) = ok(listOf(shipment))
                }

            LoadShipmentsService(queries)(setOf("order-1")) shouldBe ok(mapOf("order-1" to shipment))
        }
    })
