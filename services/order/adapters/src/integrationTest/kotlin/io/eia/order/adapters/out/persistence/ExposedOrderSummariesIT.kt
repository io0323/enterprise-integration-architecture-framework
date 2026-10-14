@file:Suppress("MagicNumber") // テストデータの時刻・金額

package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderCount
import io.eia.order.application.usecase.GetDailyOrderSummaryService
import io.eia.order.domain.AddressDraft
import io.eia.order.domain.BusinessDay
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.order.domain.OrderStatus
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Instant

private fun placed(
    id: String,
    orderedAt: String,
    unitMinor: Long = 1_000,
): Order =
    Order
        .place(
            OrderId.parse(id).ok(),
            OrderDraft(
                "cust-1",
                listOf(OrderLineDraft("prod-1", "SKU-1", 2, Money.ofMinor(unitMinor, Currency.JPY))),
                AddressDraft("JP", "100-0001", null, "千代田区", "千代田 1-1", null),
            ),
            Instant.parse(orderedAt),
        ).ok()

/** 契約の作り方(行を注文 ID のバイト順に並べ、`\n` でつないだ SHA-256)を、テストの側で独立に計算する。 */
private fun expectedDigest(lines: List<Pair<String, String>>): String {
    val text = lines.sortedWith { a, b -> compareBytes(a.first.toByteArray(), b.first.toByteArray()) }.joinToString("\n") { it.second }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

private fun compareBytes(
    a: ByteArray,
    b: ByteArray,
): Int = java.util.Arrays.compareUnsigned(a, b)

/** 日次の集計(ADR-0031 §1・§3)を、実際の PostgreSQL で確かめる。 */
class ExposedOrderSummariesIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment().also { it.start() }
        afterSpec { environment.close() }

        test("業務の 1 日(日本時間)に受け付けた注文だけを数え、ハッシュは注文 ID のバイト順(DB の照合順序に依らない)と今の状態で作る") {
            // 既定の照合順序が言語的な並びの DB(managed の PostgreSQL などでよくある)でも、バイト順で並べることを確かめる
            val db = environment.newDatabase(linguisticCollation = true)
            val repository = ExposedOrderRepository(db.database)
            val day = BusinessDay.parse("2026-10-14").ok()
            // 日本時間の 2026-10-14 は、UTC の 2026-10-13T15:00 から 2026-10-14T15:00 まで(終わりは含まない)
            val inside =
                listOf(
                    placed("ord-a", "2026-10-13T15:00:00Z", 1_000),
                    placed("ord-B", "2026-10-14T03:00:00Z", 2_500),
                    placed("orda", "2026-10-14T14:59:59.999999Z", 700),
                )
            val outside = listOf(placed("ord-prev", "2026-10-13T14:59:59.999999Z"), placed("ord-next", "2026-10-14T15:00:00Z"))
            (inside + outside).forEach { repository.insert(it).ok() }
            // 今の状態でハッシュを作る(受け付けの後に状態が変わった注文)
            val confirmed = (inside[1].transitionTo(OrderStatus.CONFIRMED).ok() as Order.Transition.Applied).order
            repository.update(confirmed).ok()

            val summary = GetDailyOrderSummaryService(ExposedOrderSummaries(db.database))(day).ok()

            summary.orderCount shouldBe 3
            summary.from shouldBe Instant.parse("2026-10-13T15:00:00Z")
            summary.until shouldBe Instant.parse("2026-10-14T15:00:00Z")
            summary.digest shouldBe
                expectedDigest(
                    listOf(
                        "ord-a" to "ord-a\tPLACED\t2000\tJPY",
                        "ord-B" to "ord-B\tCONFIRMED\t5000\tJPY",
                        "orda" to "orda\tPLACED\t1400\tJPY",
                    ),
                )
        }

        test("注文のない日は、件数 0 と空のバイト列の SHA-256") {
            val db = environment.newDatabase()
            ExposedOrderSummaries(
                db.database,
            ).count(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")).ok() shouldBe
                OrderCount(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }
    })
