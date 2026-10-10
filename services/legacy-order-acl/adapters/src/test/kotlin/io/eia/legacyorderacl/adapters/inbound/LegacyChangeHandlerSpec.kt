package io.eia.legacyorderacl.adapters.inbound

import com.github.avrokotlin.avro4k.Avro
import io.eia.legacyorderacl.adapters.FakeRegistry
import io.eia.legacyorderacl.adapters.OUTPUT_CONTENT_ID
import io.eia.legacyorderacl.adapters.RAW_TOPIC
import io.eia.legacyorderacl.adapters.TestRows
import io.eia.legacyorderacl.adapters.ok
import io.eia.legacyorderacl.adapters.outbound.KafkaLegacyOrderStatePublisher
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderChangedV1
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderStatusV1
import io.eia.legacyorderacl.application.usecase.TranslateLegacyOrderChangeService
import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.serialization.ByteArraySerializer
import kotlin.time.Duration.Companion.seconds

class LegacyChangeHandlerSpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val metrics = InMemoryMetricReader.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-acl").ok(),
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans)), metricReaders = listOf(metrics)),
                installLogAppender = false,
            )
        val registry = FakeRegistry()

        val partition = TopicPartition(RAW_TOPIC, 0)

        class Fixture(
            val output: MockProducer<ByteArray, ByteArray>,
            val dlq: MockProducer<ByteArray, ByteArray>,
            val consumer: MockConsumer<ByteArray?, ByteArray?>,
            val loop: EventConsumer,
        ) {
            fun committed(): Long? =
                consumer
                    .committed(setOf(TopicPartition(RAW_TOPIC, 0)))
                    .values
                    .singleOrNull()
                    ?.offset()

            /** ループを止める直前にコミットされていた位置(止めると Consumer が閉じるため)。 */
            var finalCommitted: Long? = null

            /** 読み取りのループ(本番と同じ run)を動かし、[records] を届けて、[done] になるまで待つ。 */
            suspend fun process(
                vararg records: ConsumerRecord<ByteArray?, ByteArray?>,
                done: Fixture.() -> Boolean = { committed() == records.maxOf { it.offset() } + 1 },
            ) = coroutineScope {
                consumer.schedulePollTask {
                    consumer.rebalance(listOf(TopicPartition(RAW_TOPIC, 0)))
                    records.forEach(consumer::addRecord)
                }
                val running = launch(Dispatchers.IO) { loop.run() }
                withTimeout(10.seconds) { while (!done()) delay(10) }
                finalCommitted = committed()
                running.cancelAndJoin()
            }
        }

        suspend fun fixture(autoComplete: Boolean = true): Fixture {
            val output = MockProducer(autoComplete, null, ByteArraySerializer(), ByteArraySerializer())
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
            val publisher =
                KafkaLegacyOrderStatePublisher(EventProducer(output, runtime, KafkaLegacyOrderStatePublisher.SOURCE), registry.book())
            val handler = LegacyChangeHandler(TranslateLegacyOrderChangeService(publisher), AclMetrics(runtime.meter))
            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            consumer.updateBeginningOffsets(mapOf(partition to 0L))
            val loop =
                EventConsumer(
                    consumer,
                    LegacyChangeHandler.GROUP_ID,
                    listOf(handler.subscription(registry.writerSchemas())),
                    DeadLetterPublisher(dlq),
                    runtime,
                )
            return Fixture(output, dlq, consumer, loop)
        }

        fun counter(name: String): Map<String, Long> =
            metrics
                .collectAllMetrics()
                .filter { it.name == name }
                .flatMap { it.longSumData.points }
                .associate { point ->
                    point.attributes
                        .asMap()
                        .values
                        .joinToString() to point.value
                }

        beforeTest { spans.reset() }

        test("登録の変更を変換し、注文番号をキーにして契約のスキーマで発行する。CONSUMER の span の下に PRODUCER の span") {
            val f = fixture()
            val record =
                TestRows.record(
                    0,
                    "J000000001",
                    TestRows.bytes(TestRows.envelope("c", after = TestRows.row("J000000001", status = "2"))),
                )

            f.process(record)

            val sent = f.output.history().single()
            sent.topic() shouldBe "sales.legacy-order.changed.v1"
            String(sent.key()) shouldBe "J000000001"
            val framed = ApicurioWireFormat.parse(sent.value()).ok()
            framed.contentId.value shouldBe OUTPUT_CONTENT_ID
            val event =
                Avro.decodeFromByteArray(
                    org.apache.avro.Schema
                        .Parser()
                        .parse(LegacyOrderEventSchemas.legacyOrderChanged.schema),
                    LegacyOrderChangedV1.serializer(),
                    framed.avroBinary,
                )
            event.orderNumber shouldBe "J000000001"
            event.customerName shouldBe "山田商事株式会社"
            event.status shouldBe LegacyOrderStatusV1.ALLOCATED
            event.totalAmount.minorUnits shouldBe 1200
            event.totalAmount.currency shouldBe "JPY"
            event.legacyUpdatedAt shouldBe null
            event.source.lsn shouldBe 26_000_000L
            val metadata = EventMetadata.fromHeaders(sent.headers()).ok()
            metadata.source shouldBe "/sales/legacy-order-acl"
            metadata.type shouldBe "sales.legacy-order.changed"
            f.dlq.history().shouldBeEmpty()
            counter("eia.acl.records") shouldBe mapOf("upserted" to 1L)

            val consumer = spans.finishedSpanItems.single { it.kind == SpanKind.CONSUMER }
            val producer = spans.finishedSpanItems.single { it.kind == SpanKind.PRODUCER }
            consumer.name shouldBe "$RAW_TOPIC process"
            producer.parentSpanId shouldBe consumer.spanId
            metadata.traceParent.traceId.toString() shouldBe consumer.traceId
        }

        test("削除の変更は、注文番号の tombstone を発行する") {
            val f = fixture()
            f
                .process(
                    TestRows.record(1, "J000000002", TestRows.bytes(TestRows.envelope("d", before = TestRows.row("J000000002")))),
                )
            val sent = f.output.history().single()
            String(sent.key()) shouldBe "J000000002"
            sent.value() shouldBe null
        }

        test("変換できない値は、受け取ったバイト列のまま DLQ に送り、原因のヘッダを付け、件数を数える。本流には送らない") {
            val f = fixture()
            val cases =
                mapOf(
                    "UNKNOWN_STATUS_CODE" to TestRows.row("J000000003", status = "7"),
                    "AMOUNT_HAS_FRACTION" to TestRows.row("J000000004", amount = "1234.50"),
                    "AMOUNT_OUT_OF_RANGE" to TestRows.row("J000000005", amount = "99999999999.00"),
                    "MALFORMED_TEXT" to TestRows.row("J000000006", name = "ｶ)ﾃｽﾄ��商事"),
                )
            val records =
                cases.values.mapIndexed { index, row ->
                    TestRows.record(index.toLong(), row.get("col_02").toString(), TestRows.bytes(TestRows.envelope("c", after = row)))
                }
            f.process(*records.toTypedArray())

            f.output.history().shouldBeEmpty()
            val dead = f.dlq.history()
            dead.map { it.topic() }.toSet() shouldBe setOf("$RAW_TOPIC.dlq")
            dead.map { String(it.headers().lastHeader("eiaf.dlq.reason").value()) } shouldBe cases.keys.toList()
            dead.forEach { record ->
                val headers = record.headers().associate { it.key() to String(it.value()) }
                // 変換できない値は、リトライせずに DLQ に送る
                headers.getValue("eiaf.dlq.attempts") shouldBe "1"
                // 値(顧客名・金額)を入れない
                headers.getValue("eiaf.dlq.detail").contains("山田") shouldBe false
                headers.getValue("eiaf.dlq.source.topic") shouldBe RAW_TOPIC
            }
            dead.first().value().toList() shouldBe TestRows.bytes(TestRows.envelope("c", after = cases.values.first())).toList()
            counter("eia.consumer.dead_letters").keys.map { it.substringAfterLast(", ") }.toSet() shouldBe cases.keys
            // 本流は止まらない: 全部のオフセットをコミットした
            f.finalCommitted shouldBe cases.size.toLong()
        }

        test("Avro として読めない値は UNDECODABLE、値がない(tombstone)は UNEXPECTED_TOMBSTONE で DLQ に送る") {
            val f = fixture()
            f.process(TestRows.record(0, "J000000007", byteArrayOf(9, 9, 9)), TestRows.record(1, "J000000008", null))
            f.dlq.history().map { String(it.headers().lastHeader("eiaf.dlq.reason").value()) } shouldBe
                listOf("UNDECODABLE", "UNEXPECTED_TOMBSTONE")
        }

        test("発行の一時的な失敗は DLQ に送らずに、その変更の位置から読み直す(ready=false)") {
            val f = fixture(autoComplete = false)
            val record = TestRows.record(0, "J000000009", TestRows.bytes(TestRows.envelope("c", after = TestRows.row("J000000009"))))
            f.process(record) {
                // 1 回目の発行を失敗させ、読み直しに入ったら終える
                if (output.history().size == 1 && loop.ready) output.errorNext(NotLeaderOrFollowerException("leader moved"))
                !loop.ready
            }
            f.dlq.history().shouldBeEmpty()
            f.finalCommitted shouldBe null
        }
    })
