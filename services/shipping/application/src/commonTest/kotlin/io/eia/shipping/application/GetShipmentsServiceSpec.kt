package io.eia.shipping.application

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.inbound.GetShipmentsUseCase
import io.eia.shipping.application.port.outbound.ShipmentQueries
import io.eia.shipping.application.usecase.GetShipmentsService
import io.eia.shipping.domain.MAX_IDENTIFIER_LENGTH
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private class FakeQueries(
    private val result: Result<List<Shipment>, DomainError>,
) : ShipmentQueries {
    var asked: Set<String>? = null

    override suspend fun find(orderIds: Set<String>): Result<List<Shipment>, DomainError> {
        asked = orderIds
        return result
    }
}

class GetShipmentsServiceSpec :
    FunSpec({
        val shipped = Shipment("saga-1", "order-1", ShipmentStatus.SHIPPED, shipmentId = "ship-1")
        val rejected = Shipment("saga-2", "order-2", ShipmentStatus.REJECTED)
        val marker = Shipment("saga-3", "order-3", ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT)

        test("出荷と拒否は返し、Saga の内部の印(取消済み)は返さない") {
            GetShipmentsService(FakeQueries(ok(listOf(shipped, rejected, marker))))(listOf("order-1", "order-2", "order-3")) shouldBe
                ok(listOf(shipped, rejected))
        }

        test("重複は 1 件として読む") {
            val queries = FakeQueries(ok(emptyList()))

            GetShipmentsService(queries)(listOf("order-1", "order-1"))

            queries.asked shouldBe setOf("order-1")
        }

        test("0 件・上限を超える・長すぎる・空の ID は ValidationError で、読まない") {
            val cases =
                listOf(
                    emptyList(),
                    List(GetShipmentsUseCase.MAX_ORDER_IDS + 1) { "order-$it" },
                    listOf("x".repeat(MAX_IDENTIFIER_LENGTH + 1)),
                    listOf(""),
                )
            cases.forEach { ids ->
                val queries = FakeQueries(ok(emptyList()))
                GetShipmentsService(queries)(ids).shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<ValidationError>()
                queries.asked shouldBe null
            }
        }

        test("上限ちょうどは通す") {
            val ids = List(GetShipmentsUseCase.MAX_ORDER_IDS) { "order-$it" }
            GetShipmentsService(FakeQueries(ok(emptyList())))(ids).shouldBeInstanceOf<Result.Ok<List<Shipment>>>()
        }

        test("読めなければ、ないことにせず失敗を返す") {
            val failure = UnavailableError("db")
            GetShipmentsService(FakeQueries(err(failure)))(listOf("order-1")) shouldBe err(failure)
        }
    })
