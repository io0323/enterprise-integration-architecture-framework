package io.eia.order.adapters.out.outbox

import io.eia.order.domain.AddressDraft
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val ORDERED_AT = Instant.parse("2026-10-07T01:02:03.456789Z")

private fun order(): Order =
    Order
        .place(
            OrderId.parse("ord-1").ok(),
            OrderDraft(
                customerId = "cust-1",
                lines =
                    listOf(
                        OrderLineDraft("prod-1", "SKU-1", 2, Money.ofMinor(1_500, Currency.JPY)),
                        OrderLineDraft("prod-2", "SKU-2", 1, Money.ofMinor(500, Currency.JPY)),
                    ),
                shippingAddress = AddressDraft("JP", "100-0001", "東京都", "千代田区", "千代田 1-1", null),
            ),
            ORDERED_AT,
        ).ok()

class OrderEventMapperSpec :
    FunSpec({
        test("注文を Canonical Model を経由して OrderCreated にする(項目は契約と同じ。金額は最小通貨単位と通貨コード)") {
            val event = OrderEventMapper.orderCreated(order()).ok()

            event.order shouldBe
                OrderV1(
                    id = "ord-1",
                    customerId = "cust-1",
                    status = OrderStatusV1.PLACED,
                    orderedAt = ORDERED_AT,
                    lines =
                        listOf(
                            OrderLineV1(1, "prod-1", "SKU-1", 2, MoneyV1(1_500, "JPY"), MoneyV1(3_000, "JPY")),
                            OrderLineV1(2, "prod-2", "SKU-2", 1, MoneyV1(500, "JPY"), MoneyV1(500, "JPY")),
                        ),
                    totalAmount = MoneyV1(3_500, "JPY"),
                    shippingAddress = AddressV1("JP", "100-0001", "千代田区", "千代田 1-1", region = "東京都"),
                )
        }

        test("契約のスキーマ(contracts からコピーしたリソース)でエンコードでき、標準の Avro ライブラリで読める") {
            val registry =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"versions":[{"contentId":3,"state":"ENABLED"}]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val book =
                SchemaIdBook(
                    OrderEventSchemas.subjects,
                    ApicurioRegistryClient(SchemaRegistryConfig("http://registry.test/apis/registry/v3"), registry),
                )
            book.resolve().ok()

            val payload = OrderEventSchemas.orderCreatedSerializer(book).serialize(OrderEventMapper.orderCreated(order()).ok()).ok()

            val framed = ApicurioWireFormat.parse(payload).ok()
            framed.contentId.value shouldBe 3
            val schema = Schema.Parser().parse(OrderEventSchemas.orderCreated.schema)
            val record = GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(framed.avroBinary, null))
            val order = record.get("order") as GenericRecord
            order.get("id").toString() shouldBe "ord-1"
            order.get("status").toString() shouldBe "PLACED"
            // timestamp-micros(ADR-0012 §3)
            order.get("orderedAt") shouldBe ORDERED_AT.epochSeconds * 1_000_000 + 456_789
            (order.get("totalAmount") as GenericRecord).get("minorUnits") shouldBe 3_500L
            (order.get("shippingAddress") as GenericRecord).get("line2") shouldBe null
        }

        test("トピックは sales.order.created.v1、スキーマは契約のファイルの内容") {
            OrderEventSchemas.ORDER_CREATED.name shouldBe "sales.order.created.v1"
            OrderEventSchemas.orderCreated.schema shouldBe
                java.io.File(checkNotNull(System.getProperty("eia.contractsAvro")), "sales/OrderCreated.avsc").readText()
        }

        test("住所は toString に出さない") {
            AddressV1("JP", "100-0001", "千代田区", "千代田 1-1").toString() shouldBe "AddressV1(countryCode=JP, ***)"
        }
    })
