package io.eia.iotbridge.adapters.inbound

import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterRecord
import io.eia.platform.messagingkafka.ReplayEntry
import io.eia.platform.messagingkafka.ReplayOutcome
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.ByteArraySerializer
import kotlin.time.Instant

private const val TOPIC = "devices/t1/d-001/telemetry"
private const val QUARANTINED = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
private val RECEIVED = Instant.parse("2026-10-25T00:00:00Z")
private val DEAD_LETTERED = Instant.parse("2026-10-25T00:00:01Z")
private const val V2 =
    """{"v":2,"boot":"0123456789abcdef","seq":1,"time":"2026-10-24T23:59:00Z","readings":[{"name":"temperature","value":20}]}"""

private val ENTRY =
    ReplayEntry(
        partition = 0,
        offset = 0,
        key = "t1/d-001",
        ceId = null,
        ceType = null,
        reason = "UNSUPPORTED_PAYLOAD_VERSION",
        detail = "v",
        sourceTopic = null,
        sourceOffset = null,
        failedAt = DEAD_LETTERED,
        replayedBefore = false,
    )

private fun dead(
    payload: String = V2,
    headers: Map<String, String> =
        mapOf(
            "eiaf.mqtt.topic" to TOPIC,
            "eiaf.mqtt.received-at" to RECEIVED.toString(),
            "traceparent" to QUARANTINED,
            "correlationid" to "corr-1",
        ),
) = DeadLetterRecord(ENTRY, "t1/d-001".encodeToByteArray(), payload.encodeToByteArray(), headers, DEAD_LETTERED)

class TelemetryDeadLetterRedeliverySpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-iot-bridge-reprocess").let { (it as Result.Ok).value },
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans))),
                installLogAppender = false,
            )
        val dlq = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())

        /** [versions] は今のデコーダが受け付ける形式の版(デコーダを足す前後を表す)。 */
        fun redelivery(
            ingest: RecordingIngest = RecordingIngest(),
            versions: Set<Int> = DevicePayloadDecoder.SUPPORTED_VERSIONS,
        ): TelemetryDeadLetterRedelivery {
            val decoder = DevicePayloadDecoder(versions)
            return TelemetryDeadLetterRedelivery(
                TelemetryMessageHandler(ingest, decoder, DeadLetterPublisher(dlq), runtime, TelemetryMetrics(runtime.meter)),
                decoder,
            )
        }

        beforeTest {
            spans.reset()
            dlq.clear()
        }

        test("dry-run は今のデコーダで読めるかだけを確かめ、発行しない(読めれば PLANNED、読めなければ REJECTED)") {
            val ingest = RecordingIngest()

            redelivery(ingest).redeliver(dead(), execute = false) shouldBe ReplayOutcome.PLANNED
            redelivery(ingest, versions = setOf(1)).redeliver(dead(), execute = false) shouldBe ReplayOutcome.REJECTED
            redelivery(ingest).redeliver(dead(headers = mapOf("eiaf.mqtt.topic" to "devices/t1/d-001/status")), execute = false) shouldBe
                ReplayOutcome.REJECTED

            ingest.received.size shouldBe 0
            dlq.history().size shouldBe 0
        }

        test("デコーダを足した後は、デコードからやり直して発行する。トピック・受け取った時刻・トレース・Correlation ID は DLQ のヘッダから") {
            val ingest = RecordingIngest()

            redelivery(ingest).redeliver(dead(), execute = true) shouldBe ReplayOutcome.REPLAYED

            val inbound = ingest.received.single()
            inbound.topic shouldBe TOPIC
            inbound.receivedAt shouldBe RECEIVED
            inbound.context.correlationId shouldBe "corr-1"
            val span = spans.finishedSpanItems.single()
            span.traceId shouldBe "0af7651916cd43dd8448eb211c80319c"
            span.parentSpanId shouldBe "b7ad6b7169203331"
            dlq.history().size shouldBe 0
        }

        test("まだ受け付けないものは REJECTED で、DLQ に入れ直さない(DLQ のレコードは残る)") {
            val ingest = RecordingIngest()

            redelivery(ingest, versions = setOf(1)).redeliver(dead(), execute = true) shouldBe ReplayOutcome.REJECTED

            ingest.received.size shouldBe 0
            dlq.history().size shouldBe 0
        }

        test("発行の一時的な失敗は FAILED(もう一度実行してよい)") {
            redelivery(RecordingIngest { err(UnavailableError("kafka")) }).redeliver(dead(), execute = true) shouldBe ReplayOutcome.FAILED
        }

        test("受け取った時刻のヘッダがなければ DLQ に入れた時刻。元のトピックのヘッダがなければ処理しない") {
            val ingest = RecordingIngest()

            redelivery(ingest).redeliver(dead(headers = mapOf("eiaf.mqtt.topic" to TOPIC)), execute = true) shouldBe ReplayOutcome.REPLAYED
            ingest.received.single().receivedAt shouldBe DEAD_LETTERED

            redelivery(ingest).redeliver(dead(headers = emptyMap()), execute = true) shouldBe ReplayOutcome.SKIPPED_SOURCE_MISMATCH
            ingest.received.size shouldBe 1
        }
    })
