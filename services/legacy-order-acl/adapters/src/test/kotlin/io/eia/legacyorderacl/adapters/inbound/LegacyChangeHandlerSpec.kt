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
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.serialization.ByteArraySerializer
import kotlin.time.Duration.Companion.seconds

class LegacyChangeProcessorSpec :
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

        suspend fun fixture(
            autoComplete: Boolean = true,
        ): Triple<MockProducer<ByteArray, ByteArray>, MockProducer<ByteArray, ByteArray>, LegacyChangeProcessor> {
            val output = MockProducer(autoComplete, null, ByteArraySerializer(), ByteArraySerializer())
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
            val publisher =
                KafkaLegacyOrderStatePublisher(EventProducer(output, runtime, KafkaLegacyOrderStatePublisher.SOURCE), registry.book())
            val processor =
                LegacyChangeProcessor(
                    TranslateLegacyOrderChangeService(publisher),
                    registry.writerSchemas(),
                    DeadLetterPublisher(dlq),
                    runtime,
                    AclMetrics(runtime.meter),
                )
            return Triple(output, dlq, processor)
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
            val (output, dlq, processor) = fixture()
            val record =
                TestRows.record(
                    0,
                    "J000000001",
                    TestRows.bytes(TestRows.envelope("c", after = TestRows.row("J000000001", status = "2"))),
                )

            processor.process(record) shouldBe LegacyChangeProcessor.Processed

            val sent = output.history().single()
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
            dlq.history().shouldBeEmpty()

            val consumer = spans.finishedSpanItems.single { it.kind == SpanKind.CONSUMER }
            val producer = spans.finishedSpanItems.single { it.kind == SpanKind.PRODUCER }
            consumer.name shouldBe "$RAW_TOPIC process"
            producer.parentSpanId shouldBe consumer.spanId
            metadata.traceParent.traceId.toString() shouldBe consumer.traceId
        }

        test("削除の変更は、注文番号の tombstone を発行する") {
            val (output, _, processor) = fixture()
            processor.process(
                TestRows.record(1, "J000000002", TestRows.bytes(TestRows.envelope("d", before = TestRows.row("J000000002")))),
            ) shouldBe
                LegacyChangeProcessor.Processed
            val sent = output.history().single()
            String(sent.key()) shouldBe "J000000002"
            sent.value() shouldBe null
        }

        test("変換できない値は、受け取ったバイト列のまま DLQ に送り、原因のヘッダを付け、件数を数える。本流には送らない") {
            val (output, dlq, processor) = fixture()
            val cases =
                mapOf(
                    "UNKNOWN_STATUS_CODE" to TestRows.row("J000000003", status = "7"),
                    "AMOUNT_HAS_FRACTION" to TestRows.row("J000000004", amount = "1234.50"),
                    "AMOUNT_OUT_OF_RANGE" to TestRows.row("J000000005", amount = "99999999999.00"),
                    "MALFORMED_TEXT" to TestRows.row("J000000006", name = "ｶ)ﾃｽﾄ��商事"),
                )
            cases.entries.forEachIndexed { index, (_, row) ->
                val value = TestRows.bytes(TestRows.envelope("c", after = row))
                processor.process(TestRows.record(10L + index, row.get("col_02").toString(), value)) shouldBe
                    LegacyChangeProcessor.Processed
            }

            output.history().shouldBeEmpty()
            val dead = dlq.history()
            dead.map { it.topic() }.toSet() shouldBe setOf("$RAW_TOPIC.dlq")
            dead.map { String(it.headers().lastHeader("eiaf.dlq.reason").value()) } shouldBe cases.keys.toList()
            dead.forEach { record ->
                val headers = record.headers().associate { it.key() to String(it.value()) }
                // 値(顧客名・金額)を入れない
                headers.getValue("eiaf.dlq.detail").contains("山田") shouldBe false
                headers.getValue("eiaf.dlq.source.topic") shouldBe RAW_TOPIC
            }
            dead.first().value().toList() shouldBe TestRows.bytes(TestRows.envelope("c", after = cases.values.first())).toList()
            counter("eia.acl.dead_letters").keys shouldBe cases.keys
        }

        test("Avro として読めない値は UNDECODABLE で DLQ に送る") {
            val (_, dlq, processor) = fixture()
            processor.process(TestRows.record(20, "J000000007", byteArrayOf(9, 9, 9))) shouldBe LegacyChangeProcessor.Processed
            processor.process(TestRows.record(21, "J000000008", null)) shouldBe LegacyChangeProcessor.Processed
            dlq.history().map { String(it.headers().lastHeader("eiaf.dlq.reason").value()) } shouldBe listOf("UNDECODABLE", "UNDECODABLE")
        }

        test("発行の一時的な失敗は RetryLater(DLQ に送らない)") {
            val (output, dlq, processor) = fixture(autoComplete = false)
            val outcome =
                coroutineScope {
                    val processing =
                        async {
                            processor.process(
                                TestRows.record(
                                    30,
                                    "J000000009",
                                    TestRows.bytes(TestRows.envelope("c", after = TestRows.row("J000000009"))),
                                ),
                            )
                        }
                    withTimeout(5.seconds) { while (output.history().isEmpty()) yield() }
                    output.errorNext(NotLeaderOrFollowerException("leader moved"))
                    processing.await()
                }
            outcome.shouldBeInstanceOf<LegacyChangeProcessor.RetryLater>().error.code shouldBe "publish_failed"
            dlq.history().shouldBeEmpty()
        }
    })
