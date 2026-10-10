@file:Suppress("MagicNumber") // 状態コード・待ち時間・wire format のバイト数

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID

private const val KAFKA = "localhost:19092"
private const val REGISTRY = "http://localhost:19081/apis/registry/v3"
private const val TOPIC = "sales.order.created.v1"

/** [orderId] をキーに持つイベントを、[timeout] まで読む。 */
private fun awaitEvent(
    orderId: String,
    timeout: Duration = Duration.ofSeconds(90),
): ConsumerRecord<String, ByteArray>? {
    val properties =
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA,
            ConsumerConfig.GROUP_ID_CONFIG to "e2e.order-events-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            // 読むだけなのでオフセットをコミットしない(コミットすると、lag が残ったままのグループが EventConsumerStalled になる)
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        )
    KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
        consumer.subscribe(listOf(TOPIC))
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == orderId }?.let { return it }
        }
    }
    return null
}

private fun header(
    record: ConsumerRecord<String, ByteArray>,
    name: String,
): String? =
    record
        .headers()
        .lastHeader(name)
        ?.value()
        ?.toString(Charsets.UTF_8)

/**
 * ROADMAP P06: 注文の作成(Gateway 経由)が、Outbox → Debezium で `sales.order.created.v1` に発行される(ADR-0007)。
 * 契約(AsyncAPI の StandardHeaders と OrderCreated.avsc)どおりかを、サービスのコードを使わずに確かめる。
 * ペイロードの wire format(先頭の 0x00 と 4 バイトの contentId。ADR-0025 §1)を読み、書き手のスキーマは Schema Registry から取る。
 */
class OrderEventE2E :
    FunSpec({
        test("注文を作ると、注文 ID のキー・CloudEvents のヘッダ・契約のスキーマのペイロードで sales.order.created.v1 に届く") {
            val created =
                sendRespectingRateLimit(
                    request(ORDERS)
                        .header("Idempotency-Key", "e2e-${UUID.randomUUID()}")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
                )
            created.statusCode() shouldBe 201
            val orderId = Regex(""""id":"([^"]+)"""").find(created.body())?.groupValues?.get(1) ?: error("注文 ID がありません")

            val record = awaitEvent(orderId) ?: error("$TOPIC に注文 $orderId のイベントが届きません")

            header(record, "ce_type") shouldBe "sales.order.created"
            header(record, "ce_source") shouldBe "/sales/order-service"
            header(record, "ce_specversion") shouldBe "1.0"
            header(record, "ce_id")!! shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            header(record, "ce_time")!! shouldMatch Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")
            header(record, "traceparent")!! shouldMatch Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
            // Correlation ID は、Gateway が付けて応答にも返した値(INTEGRATION_STANDARDS §2)
            header(record, "correlationid") shouldBe created.header("X-Correlation-Id")

            val buffer = ByteBuffer.wrap(record.value())
            buffer.get() shouldBe 0.toByte()
            val contentId = buffer.int
            val schemaJson =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("$REGISTRY/ids/contentIds/$contentId")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            schemaJson.statusCode() shouldBe 200
            val schema = Schema.Parser().parse(schemaJson.body())
            val avro = record.value().copyOfRange(5, record.value().size)
            val event = GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(avro, null))
            val order = event.get("order") as GenericRecord
            order.get("id").toString() shouldBe orderId
            order.get("status").toString() shouldBe "PLACED"
            (order.get("totalAmount") as GenericRecord).get("currency").toString() shouldBe "JPY"
        }
    })
