package io.eia.shared.canonical

import io.eia.shared.canonical.Fixtures.T0
import io.eia.shared.canonical.Fixtures.T1
import io.eia.shared.canonical.Fixtures.customer
import io.eia.shared.canonical.Fixtures.invoiceLine
import io.eia.shared.canonical.Fixtures.jpy
import io.eia.shared.canonical.Fixtures.order
import io.eia.shared.canonical.Fixtures.product
import io.eia.shared.canonical.Fixtures.rate
import io.eia.shared.canonical.Fixtures.shipment
import io.eia.shared.canonical.billing.Invoice
import io.eia.shared.canonical.billing.InvoiceDraft
import io.eia.shared.canonical.billing.InvoiceId
import io.eia.shared.canonical.billing.TaxCalculationRule
import io.eia.shared.canonical.catalog.Product
import io.eia.shared.canonical.common.CanonicalCodec
import io.eia.shared.canonical.common.MoneySerializer
import io.eia.shared.canonical.common.RateSerializer
import io.eia.shared.canonical.logistics.Shipment
import io.eia.shared.canonical.sales.Customer
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.canonical.sales.Order
import io.eia.shared.canonical.sales.OrderId
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.RoundingMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// 固定の JSON(golden)。Money は decimal 文字列 + 通貨コード、時刻は UTC の ISO 8601(ADR-0011)
private const val ORDER_JSON =
    """{"id":"o-001","customerId":"c-001","status":"PLACED","orderedAt":"2026-09-25T01:00:00Z","lines":[""" +
        """{"lineNumber":1,"productId":"p-001","sku":"SKU-001","quantity":2,"unitPrice":{"amount":"12.50","currency":"USD"},""" +
        """"lineAmount":{"amount":"25.00","currency":"USD"}},""" +
        """{"lineNumber":2,"productId":"p-002","sku":"SKU-002","quantity":1,"unitPrice":{"amount":"0.99","currency":"USD"},""" +
        """"lineAmount":{"amount":"0.99","currency":"USD"}}],"totalAmount":{"amount":"25.99","currency":"USD"},""" +
        """"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"千代田区","line1":"千代田1-1","region":null,"line2":null}}"""

class CanonicalCodecSpec :
    FunSpec({
        val codec = CanonicalCodec()
        val invoice =
            InvoiceDraft(
                InvoiceId("i-001"),
                OrderId("o-001"),
                CustomerId("c-001"),
                T0,
                T1,
                TaxCalculationRule(RoundingMode.DOWN),
                listOf(invoiceLine(1, 3, jpy(105), rate(800)), invoiceLine(2, 1, jpy(1980), rate(1000))),
                Currency.JPY,
            ).issue().shouldBeOk()

        test("Order は固定の JSON に書き出され、読み戻すと同じ値になる") {
            codec.encode(order).shouldBeOk() shouldBe ORDER_JSON
            codec.decode<Order>(ORDER_JSON).shouldBeOk() shouldBe order
        }

        test("全エンティティが JSON を往復できる") {
            codec.decode<Customer>(codec.encode(customer).shouldBeOk()).shouldBeOk() shouldBe customer
            codec.decode<Product>(codec.encode(product).shouldBeOk()).shouldBeOk() shouldBe product
            codec.decode<Shipment>(codec.encode(shipment).shouldBeOk()).shouldBeOk() shouldBe shipment
            codec.decode<Invoice>(codec.encode(invoice).shouldBeOk()).shouldBeOk() shouldBe invoice
        }

        test("Invoice は税の計算規則と率を JSON に含める") {
            val json = codec.encode(invoice).shouldBeOk()
            json shouldContain """"taxRule":{"roundingMode":"DOWN","granularity":"PER_INVOICE_PER_RATE"}"""
            json shouldContain """"taxRate":{"numerator":2,"denominator":25}"""
            json shouldContain """"taxAmount":{"amount":"25","currency":"JPY"}"""
        }

        context("decode は構造・値域・業務整合の違反を ValidationError で返す") {
            test("業務整合の違反(合計の不一致)は validate で検出する") {
                val tampered = ORDER_JSON.replace(""""totalAmount":{"amount":"25.99"""", """"totalAmount":{"amount":"30.00"""")
                val error = codec.decode<Order>(tampered).shouldBeErr()
                error.shouldBeInstanceOf<ValidationError>().violations.map { it.field } shouldBe listOf("totalAmount")
            }

            test("通貨の小数桁数を超える金額は丸めずに拒否する") {
                val tampered = ORDER_JSON.replace(""""amount":"0.99"""", """"amount":"0.995"""")
                codec.decode<Order>(tampered).shouldBeErr().message shouldContain "小数部が USD の小数桁数(2)を超えています"
            }

            test("未知の通貨は拒否し、Resolver に登録すれば受け付ける") {
                val bhd = Currency.of("BHD", 3).shouldBeOk()
                val bhdProduct = product.copy(unitPrice = Money.ofMinor(1_250, bhd))
                val json = CanonicalCodec(CurrencyResolver.of(Currency.COMMON + bhd)).encode(bhdProduct).shouldBeOk()
                json shouldContain """"unitPrice":{"amount":"1.250","currency":"BHD"}"""
                codec.decode<Product>(json).shouldBeErr().message shouldContain "未知の通貨コードです: 'BHD'"
                CanonicalCodec(CurrencyResolver.of(Currency.COMMON + bhd)).decode<Product>(json).shouldBeOk() shouldBe bhdProduct
            }

            test("構造の違反(必須項目の欠落・型の不一致・不正な率)") {
                codec.decode<Order>("""{"id":"o-001"}""").shouldBeErr().code shouldBe "validation_failed"
                codec.decode<Order>(ORDER_JSON.replace(""""quantity":2""", """"quantity":"two"""")).shouldBeErr().code shouldBe
                    "validation_failed"
                val badRate = codec.encode(invoice).shouldBeOk().replace(""""denominator":25""", """"denominator":0""")
                codec.decode<Invoice>(badRate).shouldBeErr().message shouldContain "分母は正の値です"
                codec.decode<Order>("not json").shouldBeErr().code shouldBe "validation_failed"
            }

            test("エラーメッセージに入力の JSON を含めない") {
                val error = codec.decode<Order>(ORDER_JSON.replace(""""quantity":2""", """"quantity":"two"""")).shouldBeErr()
                error.message shouldNotContain "JSON input"
                error.message shouldNotContain "千代田"
            }
        }

        test("未知のフィールドは無視する(前方互換)") {
            val withNewField = ORDER_JSON.replace(""""status":"PLACED",""", """"status":"PLACED","channel":"web",""")
            codec.decode<Order>(withNewField).shouldBeOk() shouldBe order
        }

        test("encode は不整合なエンティティを送信させない") {
            codec
                .encode(order.copy(totalAmount = jpy(1)))
                .shouldBeErr()
                .violations
                .map { it.field } shouldBe
                listOf("lines[0].lineAmount", "lines[1].lineAmount", "totalAmount")
        }

        test("Codec を通さない Json では Money を扱えない(検証の迂回を防ぐ)") {
            shouldThrow<SerializationException> { Json.encodeToString(Order.serializer(), order) }
        }

        test("Money と Rate のシリアライザは単体でも使える") {
            val json = Json
            val usdSerializer = MoneySerializer(CurrencyResolver.COMMON)
            json.encodeToString(usdSerializer, Fixtures.usd(1250)) shouldBe """{"amount":"12.50","currency":"USD"}"""
            json.decodeFromString(usdSerializer, """{"amount":"12.5","currency":"USD"}""") shouldBe Fixtures.usd(1250)
            json.encodeToString(RateSerializer, rate(1000)) shouldBe """{"numerator":1,"denominator":10}"""
        }
    })
