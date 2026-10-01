package io.eia.order.adapters.inbound.rest

import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

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

private fun request(vararg prices: MoneyDto): PlaceOrderRequestDto =
    PlaceOrderRequestDto(
        customerId = "cust-1",
        lines = prices.mapIndexed { index, price -> PlaceOrderLineDto("prod-$index", "SKU-$index", 2, price) },
        shippingAddress = AddressDto("JP", "100-0001", null, "千代田区", "千代田 1-1"),
    )

class OrderDtoMapperSpec :
    FunSpec({
        test("金額の文字列を、通貨の小数桁数で最小通貨単位に読む") {
            val draft = OrderDtoMapper.toDraft(request(MoneyDto("12.50", "USD"), MoneyDto("1500", "JPY")), CurrencyResolver.COMMON).ok()
            draft.lines.map { it.unitPrice } shouldBe listOf(Money.ofMinor(1_250, Currency.USD), Money.ofMinor(1_500, Currency.JPY))
        }

        test("金額の違反は、丸めずに項目のパス(lines[i].unitPrice.*)で返す") {
            val error =
                OrderDtoMapper
                    .toDraft(
                        request(MoneyDto("100.5", "JPY"), MoneyDto("1e3", "USD"), MoneyDto("1", "XXX")),
                        CurrencyResolver.COMMON,
                    ).error()

            error.violations.map { it.field } shouldBe
                listOf("lines[0].unitPrice.amount", "lines[1].unitPrice.amount", "lines[2].unitPrice.currency")
            error.violations[2] shouldBeEqual FieldViolation("lines[2].unitPrice.currency", "扱えない通貨です")
        }

        test("扱う通貨は注入した CurrencyResolver で決まる") {
            OrderDtoMapper.toDraft(request(MoneyDto("1", "USD")), CurrencyResolver.of(listOf(Currency.JPY))).error() shouldBeEqual
                ValidationError(listOf(FieldViolation("lines[0].unitPrice.currency", "扱えない通貨です")))
        }

        test("注文を契約の DTO にする(金額は通貨の小数桁数の 10 進、時刻は ISO 8601 の UTC)") {
            val draft = OrderDtoMapper.toDraft(request(MoneyDto("12.50", "USD")), CurrencyResolver.COMMON).ok()
            val order = Order.place(OrderId.parse("ord-1").ok(), draft, Instant.parse("2026-10-01T01:02:03.456Z")).ok()

            val dto = OrderDtoMapper.toDto(order)
            dto.orderedAt shouldBe "2026-10-01T01:02:03.456Z"
            dto.status shouldBe "PLACED"
            dto.lines.single().lineAmount shouldBe MoneyDto("25.00", "USD")
            dto.totalAmount shouldBe MoneyDto("25.00", "USD")
            dto.shippingAddress shouldBe AddressDto("JP", "100-0001", null, "千代田区", "千代田 1-1", null)
        }
    })
