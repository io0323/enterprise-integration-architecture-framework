package io.eia.order.domain

import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private val ORDERED_AT = Instant.parse("2026-09-30T01:02:03Z")

private fun yen(amount: Long): Money = Money.ofMinor(amount, Currency.JPY)

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

private val ID = OrderId.parse("ord-1").ok()

private fun address(
    countryCode: String = "JP",
    postalCode: String = "100-0001",
    city: String = "千代田区",
    line1: String = "千代田 1-1",
): AddressDraft = AddressDraft(countryCode, postalCode, region = "東京都", city = city, line1 = line1, line2 = null)

private fun line(
    quantity: Long = 2,
    unitPrice: Money = yen(1_500),
    sku: String = "SKU-1",
    productId: String = "prod-1",
): OrderLineDraft = OrderLineDraft(productId, sku, quantity, unitPrice)

private fun draft(
    lines: List<OrderLineDraft> = listOf(line(), line(quantity = 1, unitPrice = yen(980), sku = "SKU-2", productId = "prod-2")),
    customerId: String = "cust-1",
    address: AddressDraft = address(),
): OrderDraft = OrderDraft(customerId, lines, address)

private fun placed(): Order = Order.place(ID, draft(), ORDERED_AT).ok()

class OrderSpec :
    FunSpec({
        context("受け付け(Order.place)") {
            test("状態は PLACED、版は 0。明細番号は 1 からの連番で、明細の金額は単価 × 数量、合計は明細の金額の合計(税抜)") {
                val order = placed()

                order.status shouldBe OrderStatus.PLACED
                order.version shouldBe Order.INITIAL_VERSION
                order.orderedAt shouldBe ORDERED_AT
                order.lines.map { it.lineNumber } shouldContainExactly listOf(1, 2)
                order.lines.map { it.lineAmount } shouldContainExactly listOf(yen(3_000), yen(980))
                order.totalAmount shouldBeEqual yen(3_980)
            }

            test("小数桁のある通貨も最小通貨単位で正確に計算する(浮動小数点を使わない)") {
                val usd = { cents: Long -> Money.ofMinor(cents, Currency.USD) }
                val order =
                    Order
                        .place(
                            ID,
                            draft(listOf(line(quantity = 3, unitPrice = usd(10)), line(quantity = 1, unitPrice = usd(20)))),
                            ORDERED_AT,
                        ).ok()
                order.totalAmount shouldBeEqual usd(50) // 0.10 × 3 + 0.20 = 0.50
            }

            test("違反はすべて集めて返し、項目のパスは契約と同じ") {
                val error =
                    Order
                        .place(
                            ID,
                            OrderDraft(
                                customerId = " ",
                                lines =
                                    listOf(
                                        line(quantity = 0),
                                        line(unitPrice = Money.ofMinor(-1, Currency.JPY), sku = ""),
                                        line(unitPrice = Money.ofMinor(100, Currency.USD)),
                                    ),
                                shippingAddress = address(countryCode = "jp", city = ""),
                            ),
                            ORDERED_AT,
                        ).error()

                error.violations.map { it.field } shouldContainExactly
                    listOf(
                        "customerId",
                        "shippingAddress.countryCode",
                        "shippingAddress.city",
                        "lines[0].quantity",
                        "lines[1].sku",
                        "lines[1].unitPrice.amount",
                        "lines[2].unitPrice.currency",
                    )
            }

            test("明細が 0 件なら lines の違反") {
                Order.place(ID, draft(lines = emptyList()), ORDERED_AT).error() shouldBeEqual
                    ValidationError(listOf(FieldViolation("lines", "1 件以上です")))
            }

            test("単価 × 数量が金額で表せる範囲を超えたら、丸めずにエラーにする(ADR-0011 §1)") {
                val error = Order.place(ID, draft(listOf(line(quantity = Long.MAX_VALUE, unitPrice = yen(2)))), ORDERED_AT).error()
                error.violations shouldContainExactly listOf(FieldViolation("lines[0].quantity", "金額が表せる範囲を超えます"))
            }

            test("合計が金額で表せる範囲を超えたら、丸めずにエラーにする") {
                val huge = line(quantity = 1, unitPrice = yen(Long.MAX_VALUE))
                val error = Order.place(ID, draft(listOf(huge, huge)), ORDERED_AT).error()
                error.violations shouldContainExactly listOf(FieldViolation("lines", "合計の金額が表せる範囲を超えます"))
            }

            test("検証エラーの理由に、受け取った値を含めない") {
                val error = Order.place(ID, draft(customerId = "x".repeat(65) + "SECRET"), ORDERED_AT).error()
                error.message shouldNotContain "SECRET"
            }

            test("toString は配送先を伏せる(個人情報)") {
                val order = placed()
                order.toString() shouldNotContain "千代田"
                order.shippingAddress.toString() shouldBe "ShippingAddress(countryCode=JP, ***)"
                address().toString() shouldNotContain "千代田"
            }
        }

        context("状態遷移(Order.transitionTo)") {
            test("表にある遷移は Applied で、遷移前の状態を持つ。版は変えない(永続化が増やす)") {
                val applied = placed().transitionTo(OrderStatus.CONFIRMED).ok().shouldBeInstanceOf<Order.Transition.Applied>()

                applied.from shouldBe OrderStatus.PLACED
                applied.order.status shouldBe OrderStatus.CONFIRMED
                applied.order.version shouldBe Order.INITIAL_VERSION
                applied.order.id shouldBe ID
            }

            test("同じ状態への遷移は NoOp で、注文を変えない(状態変更のイベントを発行しない)") {
                val order = placed()
                val noOp = order.transitionTo(OrderStatus.PLACED).ok().shouldBeInstanceOf<Order.Transition.NoOp>()
                noOp.order shouldBeEqual order

                val cancelled = order.transitionTo(OrderStatus.CANCELLED).ok().order
                cancelled.transitionTo(OrderStatus.CANCELLED).ok().shouldBeInstanceOf<Order.Transition.NoOp>()
            }

            test("遷移表のすべての組み合わせ: 表にあれば Applied、同じ状態なら NoOp、それ以外は ConflictError") {
                OrderStatus.entries.forEach { from ->
                    val order = reach(from)
                    OrderStatus.entries.forEach { to ->
                        val result = order.transitionTo(to)
                        when {
                            to == from -> result.ok().shouldBeInstanceOf<Order.Transition.NoOp>()

                            to in
                                OrderStatus.allowedTransitions.getValue(
                                    from,
                                )
                            -> result.ok().shouldBeInstanceOf<Order.Transition.Applied>()

                            else -> result.error().shouldBeInstanceOf<ConflictError>()
                        }
                    }
                }
            }

            test("DELIVERED と CANCELLED は終端") {
                OrderStatus.entries.filter { it.isTerminal } shouldContainExactly listOf(OrderStatus.DELIVERED, OrderStatus.CANCELLED)
            }
        }

        test("restore は保存した値(版を含む)をそのまま復元する") {
            val order = placed()
            val restored =
                Order.restore(
                    order.id,
                    order.customerId,
                    OrderStatus.SHIPPED,
                    order.orderedAt,
                    order.lines.map { RestoredLine(it.lineNumber, it.productId, it.sku, it.quantity, it.unitPrice, it.lineAmount) },
                    order.totalAmount,
                    order.shippingAddress,
                    version = 7,
                )
            restored.status shouldBe OrderStatus.SHIPPED
            restored.version shouldBe 7
            restored.lines shouldBe order.lines
        }

        context("識別子") {
            test("空白・65 文字以上・制御文字は拒否し、64 文字までは受け付ける") {
                OrderId
                    .parse(" ")
                    .error()
                    .violations
                    .single()
                    .reason shouldBe "必須です"
                OrderId
                    .parse("x".repeat(65))
                    .error()
                    .violations
                    .single()
                    .reason shouldBe "64 文字以内です"
                Sku
                    .parse("a\u0000b")
                    .error()
                    .violations
                    .single()
                    .reason shouldBe "制御文字は使えません"
                ProductId
                    .parse("x".repeat(64))
                    .ok()
                    .value.length shouldBe 64
                CustomerId.parse("cust-1", field = "lines[0].customerId").ok().toString() shouldBe "cust-1"
            }
        }
    })

/** [status] の注文(PLACED から遷移表をたどって作る)。 */
private fun reach(status: OrderStatus): Order {
    val path =
        mapOf(
            OrderStatus.PLACED to emptyList(),
            OrderStatus.CONFIRMED to listOf(OrderStatus.CONFIRMED),
            OrderStatus.SHIPPED to listOf(OrderStatus.CONFIRMED, OrderStatus.SHIPPED),
            OrderStatus.DELIVERED to listOf(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED),
            OrderStatus.CANCELLED to listOf(OrderStatus.CANCELLED),
        ).getValue(status)
    return path.fold(placed()) { order, next -> order.transitionTo(next).ok().order }
}
