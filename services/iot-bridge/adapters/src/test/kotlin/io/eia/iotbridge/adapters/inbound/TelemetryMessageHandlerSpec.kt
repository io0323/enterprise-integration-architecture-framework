package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.application.port.inbound.InboundTelemetry
import io.eia.iotbridge.application.port.inbound.IngestTelemetryUseCase
import io.eia.iotbridge.domain.DeviceAddress
import io.eia.iotbridge.domain.RejectionReason
import io.eia.iotbridge.domain.Telemetry
import io.eia.iotbridge.domain.TelemetryNormalization
import io.eia.iotbridge.domain.TelemetryRejection
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.serialization.ByteArraySerializer
import kotlin.time.Instant

private val RECEIVED = Instant.parse("2026-10-25T00:00:00Z")
private const val TOPIC = "devices/t1/d-001/telemetry"
private const val PARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
private const val V2 =
    """{"v":2,"boot":"0123456789abcdef","seq":1,"time":"2026-10-24T23:59:00Z",""" +
        """"readings":[{"name":"temperature","value":20}]}"""

/** 取り込みの代わり。受けたものを記録し、[result] を返す(既定は本物と同じにそろえた結果)。 */
private class RecordingIngest(
    private val result: (InboundTelemetry) -> Result<Telemetry, DomainError> = { message ->
        DeviceAddress.fromTopic(message.topic).let { address ->
            when (address) {
                is Result.Ok -> ok(TelemetryNormalization.normalize(address.value, message.payload, message.receivedAt))
                is Result.Err -> err(address.error)
            }
        }
    },
) : IngestTelemetryUseCase {
    val received = mutableListOf<InboundTelemetry>()

    override suspend fun invoke(message: InboundTelemetry): Result<Telemetry, DomainError> {
        received += message
        return result(message)
    }
}

private fun message(
    payload: String = V2,
    topic: String = TOPIC,
    properties: List<Pair<String, String>> = listOf("traceparent" to PARENT, "correlationid" to "corr-1"),
) = ReceivedMqttMessage(topic, payload.encodeToByteArray(), properties, RECEIVED)

class TelemetryMessageHandlerSpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val metricReader = InMemoryMetricReader.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-iot-bridge").let { (it as Result.Ok).value },
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans)), metricReaders = listOf(metricReader)),
                installLogAppender = false,
            )

        fun handler(
            ingest: IngestTelemetryUseCase = RecordingIngest(),
            dlq: MockProducer<ByteArray, ByteArray> = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer()),
        ) = TelemetryMessageHandler(ingest, DevicePayloadDecoder(), DeadLetterPublisher(dlq), runtime, TelemetryMetrics(runtime.meter))

        beforeTest { spans.reset() }

        test("ユーザープロパティの traceparent の子の CONSUMER の span で処理し、Correlation ID を引き継いで発行する") {
            val ingest = RecordingIngest()

            handler(ingest).handle(message()) shouldBe HandlingResult.ACKNOWLEDGE

            val inbound = ingest.received.single()
            inbound.topic shouldBe TOPIC
            inbound.receivedAt shouldBe RECEIVED
            inbound.context.correlationId shouldBe "corr-1"
            val span = spans.finishedSpanItems.single()
            span.kind shouldBe SpanKind.CONSUMER
            span.name shouldBe "devices/+/+/telemetry process"
            span.traceId shouldBe "0af7651916cd43dd8448eb211c80319c"
            span.parentSpanId shouldBe "b7ad6b7169203331"
        }

        test("traceparent のないメッセージ(MQTT v3.1.1 のデバイス)は、新しいトレースを始め、Correlation ID を採番する") {
            val ingest = RecordingIngest()

            handler(ingest).handle(message(properties = emptyList())) shouldBe HandlingResult.ACKNOWLEDGE

            spans.finishedSpanItems
                .single()
                .parentSpanContext.isValid shouldBe false
            (
                ingest.received
                    .single()
                    .context.correlationId
                    ?.isNotBlank()
            ) shouldBe true
        }

        test("知らない版は発行せずに、受け取ったバイト列のまま DLQ に隔離して PUBACK を返す。元のトピックはヘッダ") {
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
            val ingest = RecordingIngest()
            val payload = """{"v":9,"boot":"0123456789abcdef","seq":0}"""

            handler(ingest, dlq).handle(message(payload)) shouldBe HandlingResult.ACKNOWLEDGE

            ingest.received.size shouldBe 0
            val record = dlq.history().single()
            record.topic() shouldBe "iot.telemetry.reported.v1.dlq"
            String(record.key()) shouldBe "t1/d-001"
            String(record.value()) shouldBe payload
            val headers = record.headers().associate { it.key() to String(it.value()) }
            headers["eiaf.dlq.reason"] shouldBe "UNSUPPORTED_PAYLOAD_VERSION"
            headers["eiaf.mqtt.topic"] shouldBe TOPIC
        }

        test("トピックの形が違うもの(取り込みの拒否)も DLQ に隔離する。キーはない") {
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())

            handler(dlq = dlq).handle(message(topic = "devices/t1/d-001/status")) shouldBe HandlingResult.ACKNOWLEDGE

            val record = dlq.history().single()
            record.key() shouldBe null
            record
                .headers()
                .lastHeader("eiaf.dlq.reason")
                .value()
                .decodeToString() shouldBe RejectionReason.INVALID_TOPIC.name
        }

        test("発行の一時的な失敗は RETRY(PUBACK を返さない)。DLQ には送らない") {
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())

            handler(RecordingIngest { err(UnavailableError("kafka")) }, dlq).handle(message()) shouldBe HandlingResult.RETRY

            dlq.history().size shouldBe 0
        }

        test("発行の恒久的な失敗(契約と実装の食い違いなど)は UNPUBLISHABLE で DLQ に隔離する") {
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())

            handler(RecordingIngest { err(UnexpectedError("encoding")) }, dlq).handle(message()) shouldBe HandlingResult.ACKNOWLEDGE

            dlq
                .history()
                .single()
                .headers()
                .lastHeader("eiaf.dlq.reason")
                .value()
                .decodeToString() shouldBe "UNPUBLISHABLE"
        }

        test("DLQ に送れなければ RETRY(PUBACK を返さず、隔離できるまで送り直す)") {
            val dlq = MockProducer(false, null, ByteArraySerializer(), ByteArraySerializer())
            val handler = handler(dlq = dlq)
            val result =
                coroutineScope {
                    val pending = async { handler.handle(message("""{"v":9}""")) }
                    while (dlq.history().isEmpty()) yield()
                    dlq.errorNext(NotLeaderOrFollowerException("down"))
                    pending.await()
                }

            result shouldBe HandlingResult.RETRY
        }

        test("拒否の理由は TelemetryRejection のまま DLQ の理由になる") {
            val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())

            handler(RecordingIngest { err(TelemetryRejection(RejectionReason.INVALID_PAYLOAD, "/seq:minimum")) }, dlq).handle(message())

            val headers = dlq.history().single().headers()
            headers.lastHeader("eiaf.dlq.reason").value().decodeToString() shouldBe "INVALID_PAYLOAD"
            headers.lastHeader("eiaf.dlq.detail").value().decodeToString() shouldBe "/seq:minimum"
        }
    })
