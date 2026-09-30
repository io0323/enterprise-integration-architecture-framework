package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.order.domain.AddressDraft
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.order.domain.OrderStatus
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLException
import java.sql.Timestamp
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private val ORDERED_AT = Instant.parse("2026-10-01T09:30:00.123456Z")

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

private val PLACED: Order =
    Order
        .place(
            OrderId.parse("ord-1").ok(),
            OrderDraft(
                "cust-1",
                listOf(
                    OrderLineDraft("prod-1", "SKU-1", 3, Money.ofMinor(1_050, Currency.USD)),
                    OrderLineDraft("prod-2", "SKU-2", 1, Money.ofMinor(99, Currency.USD)),
                ),
                AddressDraft("JP", "100-0001", null, "千代田区", "千代田 1-1", "3F"),
            ),
            ORDERED_AT,
        ).ok()

/** [PLACED] を保存した行([overrides] で列を差し替える)。 */
private fun orderRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> =
    mapOf(
        "id" to "ord-1",
        "customer_id" to "cust-1",
        "status" to "PLACED",
        "ordered_at" to Timestamp.from(ORDERED_AT.toJavaInstant()),
        "currency" to "USD",
        "total_amount_minor" to 3_249L,
        "ship_country_code" to "JP",
        "ship_postal_code" to "100-0001",
        "ship_region" to null,
        "ship_city" to "千代田区",
        "ship_line1" to "千代田 1-1",
        "ship_line2" to "3F",
        "version" to 0L,
    ) + overrides

private fun lineRows(sku: String = "SKU-1"): List<Map<String, Any?>> =
    listOf(
        mapOf(
            "line_number" to 1,
            "product_id" to "prod-1",
            "sku" to sku,
            "quantity" to 3L,
            "unit_price_minor" to 1_050L,
            "line_amount_minor" to 3_150L,
        ),
        mapOf(
            "line_number" to 2,
            "product_id" to "prod-2",
            "sku" to "SKU-2",
            "quantity" to 1L,
            "unit_price_minor" to 99L,
            "line_amount_minor" to 99L,
        ),
    )

private fun FakeJdbc.returning(
    order: Map<String, Any?>?,
    lines: List<Map<String, Any?>> = lineRows(),
    exists: Boolean = order != null,
) {
    rows = { sql, _ ->
        when {
            sql.startsWith("SELECT id") -> listOfNotNull(order)
            sql.startsWith("SELECT line_number") -> lines
            sql.startsWith("SELECT 1") -> if (exists) listOf(mapOf("?column?" to 1)) else emptyList()
            else -> emptyList()
        }
    }
}

private fun repository(
    jdbc: FakeJdbc,
    currencies: CurrencyResolver = CurrencyResolver.COMMON,
) = ExposedOrderRepository(jdbc.session, currencies)

class ExposedOrderRepositorySpec :
    FunSpec({
        context("insert") {
            test("注文(13 列)と明細(明細ごとに 7 列。バッチ)を書く") {
                val jdbc = FakeJdbc()
                repository(jdbc).insert(PLACED).ok()

                val (orderInsert, line1, line2) = jdbc.executed
                orderInsert.sql.startsWith("INSERT INTO orders") shouldBe true
                orderInsert.params shouldBe
                    mapOf(
                        1 to "ord-1",
                        2 to "cust-1",
                        3 to "PLACED",
                        4 to Timestamp.from(ORDERED_AT.toJavaInstant()),
                        5 to "USD",
                        6 to 3_249L,
                        7 to "JP",
                        8 to "100-0001",
                        9 to null,
                        10 to "千代田区",
                        11 to "千代田 1-1",
                        12 to "3F",
                        13 to 0L,
                    )
                line1.params shouldBe mapOf(1 to "ord-1", 2 to 1, 3 to "prod-1", 4 to "SKU-1", 5 to 3L, 6 to 1_050L, 7 to 3_150L)
                line2.params[2] shouldBe 2
            }

            test("一意制約の違反(23505)は ConflictError。理由に値を入れない") {
                val jdbc = FakeJdbc().apply { failOn = { SQLException("Key (id)=(ord-1) already exists", SqlErrors.UNIQUE_VIOLATION) } }
                val error = repository(jdbc).insert(PLACED).error().shouldBeInstanceOf<ConflictError>()
                error.message shouldNotContain "already exists"
            }

            test("そのほかの SQL の例外は SQLSTATE で分類する(接続の失敗は Retryable)") {
                val jdbc = FakeJdbc().apply { failOn = { SQLException("connection refused", "08001") } }
                repository(jdbc).insert(PLACED).error().shouldBeInstanceOf<UnavailableError>()
            }
        }

        context("findById") {
            test("注文と明細の行から、保存した注文をそのまま復元する") {
                val jdbc = FakeJdbc().apply { returning(orderRow()) }
                repository(jdbc).findById(PLACED.id).ok()?.shouldBeEqual(PLACED)
                jdbc.executed.map { it.params[1] } shouldBe listOf("ord-1", "ord-1")
            }

            test("なければ null") {
                val jdbc = FakeJdbc().apply { returning(null) }
                repository(jdbc).findById(PLACED.id).ok() shouldBe null
            }

            listOf(
                Triple("通貨を解決できない", orderRow(mapOf("currency" to "XXX")), "currency"),
                Triple("状態が不正", orderRow(mapOf("status" to "UNKNOWN")), "status"),
                Triple("ID が不正", orderRow(mapOf("id" to " ")), "id"),
                Triple("配送先が不正", orderRow(mapOf("ship_country_code" to "jp")), "ship_*"),
            ).forEach { (name, row, field) ->
                test("保存された値が不正なら UnexpectedError(項目の名前だけ): $name") {
                    val jdbc = FakeJdbc().apply { returning(row) }
                    repository(jdbc).findById(PLACED.id).error() shouldBeEqual UnexpectedError("保存された注文の値が不正です($field)")
                }
            }

            test("明細の値が不正なら UnexpectedError") {
                val jdbc = FakeJdbc().apply { returning(orderRow(), lineRows(sku = "")) }
                repository(jdbc).findById(PLACED.id).error() shouldBeEqual UnexpectedError("保存された注文の値が不正です(sku)")
            }

            test("注文の通貨は、注入した CurrencyResolver で戻す") {
                val jpy = CurrencyResolver.of(listOf(Currency.JPY))
                val jdbc = FakeJdbc().apply { returning(orderRow()) }
                repository(jdbc, jpy).findById(PLACED.id).error().shouldBeInstanceOf<UnexpectedError>()
            }
        }

        context("update(楽観的ロック)") {
            val confirmed = PLACED.transitionTo(OrderStatus.CONFIRMED).ok().order

            test("読んだ版のまま保存されていれば(1 件更新)、版を 1 増やした注文を返す。状態・ID・版を渡す") {
                val jdbc = FakeJdbc()
                repository(jdbc).update(confirmed).ok().version shouldBe 1
                jdbc.executed.single().params shouldBe mapOf(1 to "CONFIRMED", 2 to "ord-1", 3 to 0L)
                jdbc.executed.single().sql shouldBe
                    "UPDATE orders SET status = ?, version = version + 1, updated_at = now() WHERE id = ? AND version = ?"
            }

            test("0 件で注文があれば OrderVersionConflict") {
                val jdbc =
                    FakeJdbc().apply {
                        updated = { 0 }
                        returning(orderRow())
                    }
                repository(jdbc).update(confirmed).error() shouldBeEqual OrderVersionConflict(PLACED.id, 0)
            }

            test("0 件で注文がなければ NotFoundError") {
                val jdbc =
                    FakeJdbc().apply {
                        updated = { 0 }
                        returning(null)
                    }
                repository(jdbc).update(confirmed).error() shouldBeEqual NotFoundError("order", "ord-1")
            }
        }
    })
