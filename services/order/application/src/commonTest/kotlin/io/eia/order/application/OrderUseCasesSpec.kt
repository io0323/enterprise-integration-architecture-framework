package io.eia.order.application

import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.order.application.usecase.GetOrderService
import io.eia.order.application.usecase.PlaceOrderService
import io.eia.order.domain.AddressDraft
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.order.domain.OrderStatus
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private val NOW = Instant.parse("2026-09-30T12:00:00Z")

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <T, E> Result<T, E>.error(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

private fun draft(quantity: Long = 2): OrderDraft =
    OrderDraft(
        customerId = "cust-1",
        lines = listOf(OrderLineDraft("prod-1", "SKU-1", quantity, Money.ofMinor(1_500, Currency.JPY))),
        shippingAddress = AddressDraft("JP", "100-0001", "東京都", "千代田区", "千代田 1-1", null),
    )

private class Fixture {
    val transaction = FakeTransactionRunner()
    val repository = FakeOrderRepository(transaction)
    val ids = SequentialIds()
    val place = PlaceOrderService(repository, transaction, ids, FixedClock(NOW))
    val get = GetOrderService(repository)
}

class OrderUseCasesSpec :
    FunSpec({
        context("PlaceOrderUseCase") {
            test("採番した ID と時計の時刻で受け付け、トランザクションの中で保存する") {
                val f = Fixture()
                val order = f.place(draft()).ok()

                order.id.value shouldBe "ord-1"
                order.orderedAt shouldBe NOW
                order.status shouldBe OrderStatus.PLACED
                order.totalAmount shouldBeEqual Money.ofMinor(3_000, Currency.JPY)
                f.repository.orders[order.id] shouldBe order
                f.transaction.commits shouldBe 1
            }

            test("違反があれば ValidationError を返し、保存しない(トランザクションも始めない)") {
                val f = Fixture()
                f.place(draft(quantity = 0)).error().shouldBeInstanceOf<ValidationError>()

                f.repository.orders shouldBe emptyMap()
                f.transaction.commits shouldBe 0
                f.transaction.rollbacks shouldBe 0
            }

            test("保存に失敗したら、そのエラーを返してトランザクションを取り消す") {
                val f = Fixture()
                val unavailable = UnavailableError("DB に接続できません")
                f.repository.failWith = unavailable

                f.place(draft()).error() shouldBeEqual unavailable
                f.transaction.rollbacks shouldBe 1
                f.repository.orders shouldBe emptyMap()
            }

            test("同じ ID の注文があれば ConflictError(採番の重複)") {
                val f = Fixture()
                f.place(draft()).ok()
                f.ids.issued = 0 // 同じ ID をもう一度採番させる

                f.place(draft()).error().shouldBeInstanceOf<ConflictError>()
                f.repository.orders.size shouldBe 1
            }
        }

        context("GetOrderUseCase") {
            test("保存した注文を返す") {
                val f = Fixture()
                val placed = f.place(draft()).ok()
                f.get(placed.id).ok() shouldBe placed
            }

            test("なければ NotFoundError") {
                val f = Fixture()
                val id = OrderId.parse("ord-404").ok()
                f.get(id).error() shouldBeEqual NotFoundError("order", "ord-404")
            }

            test("読み込みに失敗したら、そのエラーを返す") {
                val f = Fixture()
                val unavailable = UnavailableError("DB に接続できません")
                f.repository.failWith = unavailable
                f.get(OrderId.parse("ord-1").ok()).error() shouldBeEqual unavailable
            }
        }

        context("OrderRepository の楽観的ロックの約束(④a の実装が満たすこと)") {
            test("読んだ版のまま保存されていれば更新して版を 1 増やし、先に別の更新が確定していれば OrderVersionConflict") {
                val f = Fixture()
                val placed = f.place(draft()).ok()
                val confirmed = placed.transitionTo(OrderStatus.CONFIRMED).ok().order
                val cancelled = placed.transitionTo(OrderStatus.CANCELLED).ok().order

                // 取消が先に確定する
                f.repository
                    .update(cancelled)
                    .ok()
                    .version shouldBe 1
                // 同じ版(0)を読んでいた確定は衝突する
                f.repository.update(confirmed).error() shouldBeEqual OrderVersionConflict(placed.id, 0)

                // 読み直すと CANCELLED なので、確定は遷移表で拒否される(Saga は補償に進む)
                val latest = f.get(placed.id).ok()
                latest.status shouldBe OrderStatus.CANCELLED
                latest.transitionTo(OrderStatus.CONFIRMED).error().shouldBeInstanceOf<ConflictError>()
                // 同じ取消が遅れて届いても NoOp(イベントを発行しない)
                latest.transitionTo(OrderStatus.CANCELLED).ok().shouldBeInstanceOf<Order.Transition.NoOp>()
            }

            test("OrderVersionConflict は NonRetryable で、メッセージは注文 ID と読んだ版だけを含む") {
                val conflict = OrderVersionConflict(OrderId.parse("ord-9").ok(), 3)
                conflict.shouldBeInstanceOf<DomainError.NonRetryable>()
                conflict.code shouldBe "order_version_conflict"
                conflict.message shouldBe "注文 ord-9 は、読んだ版 3 の後に更新されています"
            }
        }
    })
