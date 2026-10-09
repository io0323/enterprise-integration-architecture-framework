package io.eia.order.application

import io.eia.order.application.port.inbound.PlaceOrderCommand
import io.eia.order.application.port.inbound.ReplyOutcome
import io.eia.order.application.port.inbound.RequestedBy
import io.eia.order.application.port.inbound.SagaReply
import io.eia.order.application.port.outbound.SagaObserver
import io.eia.order.application.usecase.HandleSagaReplyService
import io.eia.order.application.usecase.PlaceOrderService
import io.eia.order.application.usecase.SagaCoordinator
import io.eia.order.application.usecase.SagaTimeouts
import io.eia.order.application.usecase.TimeoutSagasService
import io.eia.order.domain.AddressDraft
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.order.domain.OrderStatus
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaCommand
import io.eia.order.domain.SagaFailure
import io.eia.order.domain.SagaSignal
import io.eia.order.domain.SagaState
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private class RecordingObserver : SagaObserver {
    val events = mutableListOf<String>()

    override fun transitioned(
        saga: Saga,
        from: SagaState,
    ) {
        events += "$from->${saga.state}"
    }

    override fun resent(saga: Saga) {
        events += "resent:${saga.state}:${saga.resends}"
    }

    override fun ignored(
        state: SagaState,
        signal: SagaSignal,
    ) {
        events += "ignored:$state:$signal"
    }
}

private val TIMEOUTS = SagaTimeouts(step = 20.seconds, compensation = 5.seconds, stallAfterResends = 2)

private class World {
    val transaction = FakeTransactionRunner()
    val repository = FakeOrderRepository(transaction)
    val events = FakeOrderEventOutbox(transaction)
    val sagas = FakeSagaStore(transaction)
    val commands = FakeSagaCommands(transaction)
    val observer = RecordingObserver()
    val coordinator = SagaCoordinator(sagas, repository, commands, events, SequentialSagaIds(), TIMEOUTS, observer)
    private val place =
        PlaceOrderService(
            repository,
            transaction,
            SequentialIds(),
            FixedClock(Instant.parse("2026-10-09T00:00:00Z")),
            FakeOrderAuditTrail(transaction),
            events,
            coordinator,
        )
    val reply = HandleSagaReplyService(transaction, FakeProcessedReplies(transaction), sagas, coordinator)
    val timeouts = TimeoutSagasService(transaction, sagas, coordinator, batchSize = 2)
    private var messages = 0

    suspend fun placeOrder(): OrderId {
        val draft =
            OrderDraft(
                "cust-1",
                listOf(OrderLineDraft("prod-1", "SKU-1", 2, Money.ofMinor(1_500, Currency.JPY))),
                AddressDraft("JP", "100-0001", "東京都", "千代田区", "千代田 1-1", null),
            )
        return place(PlaceOrderCommand(draft, RequestedBy("client-a"), null)).ok().id
    }

    suspend fun signal(
        signal: SagaSignal,
        saga: String = "saga-1",
        id: String = "m-${++messages}",
    ) = reply(SagaReply(id, "t", saga, signal))

    fun saga(id: String = "saga-1") = sagas.sagas.getValue(id)

    fun orderStatus(id: OrderId) = repository.orders.getValue(id).status
}

class SagaServicesSpec :
    FunSpec({
        test("正常: 在庫の引当 → 決済の承認(注文 CONFIRMED)→ 出荷(注文 SHIPPED)で完了し、各段でコマンドと期限を書く") {
            val w = World()
            val order = w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVED).ok()
            w.sagas.timeouts["saga-1"] shouldBe 20.seconds
            w.signal(SagaSignal.PAYMENT_AUTHORIZED).ok()
            w.orderStatus(order) shouldBe OrderStatus.CONFIRMED
            w.signal(SagaSignal.SHIPMENT_SHIPPED).ok()

            w.saga().state shouldBe SagaState.COMPLETED
            w.sagas.timeouts["saga-1"] shouldBe null
            w.orderStatus(order) shouldBe OrderStatus.SHIPPED
            w.commands.sent.map { it.second } shouldBe
                listOf(SagaCommand.RESERVE_STOCK, SagaCommand.AUTHORIZE_PAYMENT, SagaCommand.ARRANGE_SHIPMENT)
            w.events.cancelled shouldBe emptyList()
        }

        test("在庫不足: 補償なしで COMPENSATED、注文 CANCELLED と sales.order.cancelled.v1(STOCK_UNAVAILABLE)") {
            val w = World()
            val order = w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVATION_REJECTED).ok()

            w.saga() shouldBe Saga("saga-1", order, SagaState.COMPENSATED, SagaFailure.STOCK_UNAVAILABLE)
            w.orderStatus(order) shouldBe OrderStatus.CANCELLED
            w.events.cancelled shouldBe listOf(order to SagaFailure.STOCK_UNAVAILABLE)
        }

        test("決済の失敗: 在庫を解放してから COMPENSATED。補償の段の期限は送り直しの間隔") {
            val w = World()
            val order = w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVED).ok()
            w.signal(SagaSignal.PAYMENT_DECLINED).ok()
            w.saga().state shouldBe SagaState.RELEASING_STOCK
            w.sagas.timeouts["saga-1"] shouldBe 5.seconds
            w.orderStatus(order) shouldBe OrderStatus.PLACED
            w.signal(SagaSignal.STOCK_RELEASED).ok()

            w.orderStatus(order) shouldBe OrderStatus.CANCELLED
            w.events.cancelled shouldBe listOf(order to SagaFailure.PAYMENT_DECLINED)
            w.commands.sent.last() shouldBe ("saga-1" to SagaCommand.RELEASE_STOCK)
        }

        test("期限切れ: 前進の段は補償へ、補償の段は同じコマンドを送り直す(回数を数え、段が変われば 0 に戻す)") {
            val w = World()
            val order = w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVED).ok()
            w.sagas.expired += "saga-1"
            w.timeouts().ok() shouldBe 1
            w.saga().state shouldBe SagaState.VOIDING_PAYMENT
            w.saga().failure shouldBe SagaFailure.TIMED_OUT

            repeat(3) {
                w.sagas.expired += "saga-1"
                w.timeouts().ok()
            }
            w.saga().resends shouldBe 3
            w.commands.sent.count { it.second == SagaCommand.VOID_PAYMENT } shouldBe 4
            w.observer.events.filter { it.startsWith("resent") } shouldBe
                listOf("resent:VOIDING_PAYMENT:1", "resent:VOIDING_PAYMENT:2", "resent:VOIDING_PAYMENT:3")

            w.signal(SagaSignal.PAYMENT_VOIDED).ok()
            w.saga().resends shouldBe 0
            w.signal(SagaSignal.STOCK_RELEASED).ok()
            w.orderStatus(order) shouldBe OrderStatus.CANCELLED
            w.events.cancelled shouldBe listOf(order to SagaFailure.TIMED_OUT)
        }

        test("出荷の期限切れで取消を送ったが出荷済みだった: 補償をやめて完了(注文 SHIPPED。決済の取消は送らない)") {
            val w = World()
            val order = w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVED).ok()
            w.signal(SagaSignal.PAYMENT_AUTHORIZED).ok()
            w.sagas.expired += "saga-1"
            w.timeouts().ok()
            w.signal(SagaSignal.SHIPMENT_SHIPPED).ok()
            w.saga().state shouldBe SagaState.CANCELLING_SHIPMENT
            w.signal(SagaSignal.SHIPMENT_ALREADY_SHIPPED).ok()

            w.saga().state shouldBe SagaState.COMPLETED
            w.orderStatus(order) shouldBe OrderStatus.SHIPPED
            w.commands.sent
                .map { it.second }
                .contains(SagaCommand.VOID_PAYMENT) shouldBe false
            w.observer.events shouldBe
                listOf(
                    "RESERVING_STOCK->AUTHORIZING_PAYMENT",
                    "AUTHORIZING_PAYMENT->ARRANGING_SHIPMENT",
                    "ARRANGING_SHIPMENT->CANCELLING_SHIPMENT",
                    "ignored:CANCELLING_SHIPMENT:SHIPMENT_SHIPPED",
                    "CANCELLING_SHIPMENT->COMPLETED",
                )
        }

        test("同じ返信(同じ ce_id)は DUPLICATE で何もしない。知らない Saga ID は NotFound(DLQ)") {
            val w = World()
            w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVED, id = "m-x").ok() shouldBe ReplyOutcome.PROCESSED
            w.signal(SagaSignal.STOCK_RESERVED, id = "m-x").ok() shouldBe ReplyOutcome.DUPLICATE
            w.commands.sent.size shouldBe 2

            (
                w.signal(
                    SagaSignal.STOCK_RESERVED,
                    saga = "saga-unknown",
                ) as Result.Err<DomainError>
            ).error.shouldBeInstanceOf<NotFoundError>()
        }

        test("書き込みに失敗すれば、Saga・注文・コマンドの全部を取り消す(次の受信でやり直せる)") {
            val w = World()
            val order = w.placeOrder()
            w.signal(SagaSignal.STOCK_RESERVED).ok()
            w.commands.failWith =
                io.eia.shared.kernel
                    .UnavailableError("Outbox に書けない")
            w.signal(SagaSignal.PAYMENT_AUTHORIZED).shouldBeInstanceOf<Result.Err<DomainError>>()

            w.saga().state shouldBe SagaState.AUTHORIZING_PAYMENT
            w.orderStatus(order) shouldBe OrderStatus.PLACED
        }

        test("期限切れの処理は、上限の件数ずつ、期限を過ぎた Saga がなくなるまで繰り返す") {
            val w = World()
            repeat(5) { w.placeOrder() }
            w.sagas.expired += (1..5).map { "saga-$it" }
            w.timeouts().ok() shouldBe 5
            w.sagas.sagas.values
                .map { it.state }
                .toSet() shouldBe setOf(SagaState.RELEASING_STOCK)
        }

        test("期限の設定: 終端は期限なし、補償の段は送り直しの間隔、前進の段は段の期限。誤った値は拒否") {
            TIMEOUTS.of(SagaState.COMPLETED) shouldBe null
            TIMEOUTS.of(SagaState.RELEASING_STOCK) shouldBe 5.seconds
            TIMEOUTS.of(SagaState.ARRANGING_SHIPMENT) shouldBe 20.seconds
            (runCatchingRequire { SagaTimeouts(step = 0.seconds) }) shouldBe true
            (runCatchingRequire { SagaTimeouts(stallAfterResends = 0) }) shouldBe true
            (
                runCatchingRequire {
                    TimeoutSagasService(FakeTransactionRunner(), FakeSagaStore(FakeTransactionRunner()), World().coordinator, 0)
                }
            ) shouldBe
                true
        }
    })

private inline fun runCatchingRequire(block: () -> Unit): Boolean =
    try {
        block()
        false
    } catch (_: IllegalArgumentException) {
        true
    }
