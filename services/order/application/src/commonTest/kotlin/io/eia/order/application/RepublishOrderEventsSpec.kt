package io.eia.order.application

import io.eia.order.application.port.inbound.RepublishOrderEventsCommand
import io.eia.order.application.port.inbound.RepublishReport
import io.eia.order.application.port.inbound.RequestedBy
import io.eia.order.application.port.outbound.RepublishMark
import io.eia.order.application.port.outbound.RepublishableOrder
import io.eia.order.application.port.outbound.RepublishableOrders
import io.eia.order.application.usecase.RepublishOrderEventsService
import io.eia.order.domain.AddressDraft
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private val FROM = Instant.parse("2026-11-01T00:00:00Z")
private val TO = Instant.parse("2026-11-02T00:00:00Z")
private val OPERATOR = RequestedBy("ops-1")

private fun command(
    execute: Boolean,
    from: Instant = FROM,
    to: Instant = TO,
) = RepublishOrderEventsCommand(from, to, "slot-lost", "run-1", execute, OPERATOR)

private class FakeRepublishableOrders(
    private val items: List<RepublishableOrder>,
    private val failAfter: Int = Int.MAX_VALUE,
) : RepublishableOrders {
    val pages = mutableListOf<String?>()

    override suspend fun page(
        from: Instant,
        to: Instant,
        afterId: String?,
        limit: Int,
    ): Result<List<RepublishableOrder>, DomainError> {
        pages += afterId
        if (pages.size > failAfter) return err(UnavailableError("DB に接続できません"))
        val start = if (afterId == null) 0 else items.indexOfFirst { it.order.id.value == afterId } + 1
        return ok(items.drop(start).take(limit))
    }
}

private class RepublishFixture(
    orders: FakeRepublishableOrders,
    batchSize: Int = 2,
) {
    val transactions = FakeTransactionRunner()
    val events = FakeOrderEventOutbox(transactions)
    val audit = FakeOrderAuditTrail(transactions)
    val service = RepublishOrderEventsService(orders, events, audit, transactions, batchSize)
}

private fun items(count: Int): List<RepublishableOrder> = List(count) { RepublishableOrder(sampleOrder(it), null, FROM) }

class RepublishOrderEventsSpec :
    FunSpec({
        test("dry-run は件数を数えるだけで、Outbox にも監査にも書かない") {
            val fixture = RepublishFixture(FakeRepublishableOrders(items(3)))

            fixture.service(command(execute = false)) shouldBe ok(RepublishReport(3, executed = false))

            fixture.events.republished shouldBe emptyList()
            fixture.audit.republished shouldBe emptyList()
        }

        test("実行はページごとに 1 つのトランザクションで書き、目印を付ける。監査は最後に 1 件だけ") {
            val orders = FakeRepublishableOrders(items(3))
            val fixture = RepublishFixture(orders)

            fixture.service(command(execute = true)) shouldBe ok(RepublishReport(3, executed = true))

            fixture.events.republished
                .map { it.second }
                .toSet() shouldBe setOf(RepublishMark("slot-lost", "run-1"))
            fixture.events.republished.size shouldBe 3
            // 2 件 + 1 件 + 監査
            fixture.transactions.commits shouldBe 3
            fixture.audit.republished shouldBe listOf(Triple("run-1", 3L, OPERATOR))
            orders.pages shouldBe listOf(null, sampleOrder(1).id.value, sampleOrder(2).id.value)
        }

        test("対象がなくても実行は監査に残す(0 件で実行したことが分かる)") {
            val fixture = RepublishFixture(FakeRepublishableOrders(emptyList()))

            fixture.service(command(execute = true)) shouldBe ok(RepublishReport(0, executed = true))

            fixture.audit.republished shouldBe listOf(Triple("run-1", 0L, OPERATOR))
        }

        test("範囲の始まりが終わりより後なら誤り。何も読まない") {
            val orders = FakeRepublishableOrders(items(1))
            val result = RepublishFixture(orders).service(command(execute = true, from = TO, to = FROM))

            result.shouldBeInstanceOf<Result.Err<ValidationError>>()
            orders.pages shouldBe emptyList()
        }

        test("途中で読めなくなると失敗を返す。書いた分は確定したまま。監査には残さない") {
            val fixture = RepublishFixture(FakeRepublishableOrders(items(3), failAfter = 1))

            fixture.service(command(execute = true)).shouldBeInstanceOf<Result.Err<DomainError>>()

            fixture.events.republished.size shouldBe 2
            fixture.audit.republished shouldBe emptyList()
        }

        test("Outbox に書けなければ、そのページは確定しない") {
            val fixture = RepublishFixture(FakeRepublishableOrders(items(2)))
            fixture.events.failWith = UnavailableError("DB に接続できません")

            fixture.service(command(execute = true)).shouldBeInstanceOf<Result.Err<DomainError>>()

            fixture.events.republished shouldBe emptyList()
            fixture.transactions.commits shouldBe 0
        }
    })

private fun sampleOrder(index: Int): Order {
    val id = (OrderId.parse("order-$index") as? Result.Ok)?.value ?: fail("注文の ID が不正です")
    val draft =
        OrderDraft(
            "cust-1",
            listOf(OrderLineDraft("prod-1", "SKU-1", 1, Money.ofMinor(1_500, Currency.JPY))),
            AddressDraft("JP", "100-0001", null, "千代田区", "千代田 1-1", null),
        )
    return (Order.place(id, draft, FROM) as? Result.Ok)?.value ?: fail("注文を作れません")
}
