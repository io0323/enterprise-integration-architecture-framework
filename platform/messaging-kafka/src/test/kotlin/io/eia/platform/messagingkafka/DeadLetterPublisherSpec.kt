package io.eia.platform.messagingkafka

import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.FixedClock
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.util.Optional
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val FAILED_AT = Instant.parse("2026-10-08T05:00:00Z")

private fun consumed(): ConsumerRecord<ByteArray?, ByteArray?> =
    ConsumerRecord(
        "_cdc.legacy.public.t_juchu",
        2,
        41L,
        0L,
        TimestampType.CREATE_TIME,
        0,
        0,
        "J000000001".toByteArray(),
        byteArrayOf(0, 0, 0, 0, 7, 1, 2, 3),
        RecordHeaders(
            listOf(
                RecordHeader("origin", "debezium".toByteArray()),
                RecordHeader(TraceParent.HEADER, "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".toByteArray()),
            ),
        ),
        Optional.empty(),
    )

class DeadLetterPublisherSpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-dlq").ok(),
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans))),
                installLogAppender = false,
            )

        test("受け取ったキーと値をそのまま {topic}.dlq に送り、原因のヘッダを加える。traceparent は処理したトレースに置き換える") {
            val mock = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
            val publisher = DeadLetterPublisher(mock, FixedClock(FAILED_AT))

            val (sent, spanId) =
                runtime.withSpan("process") { span ->
                    publisher.send(consumed(), DeadLetterReason("UNKNOWN_STATUS_CODE", "col_03: 状態区分の対応表にないコード")).ok() to
                        span.spanContext.spanId
                }

            val record = mock.history().single()
            sent.topic shouldBe "_cdc.legacy.public.t_juchu.dlq"
            record.topic() shouldBe "_cdc.legacy.public.t_juchu.dlq"
            String(record.key()) shouldBe "J000000001"
            record.value().toList() shouldBe listOf<Byte>(0, 0, 0, 0, 7, 1, 2, 3)
            val headers = record.headers().associate { it.key() to String(it.value()) }
            headers shouldBe
                mapOf(
                    "origin" to "debezium",
                    TraceParent.HEADER to headers.getValue(TraceParent.HEADER),
                    "eiaf.dlq.reason" to "UNKNOWN_STATUS_CODE",
                    "eiaf.dlq.detail" to "col_03: 状態区分の対応表にないコード",
                    "eiaf.dlq.source.topic" to "_cdc.legacy.public.t_juchu",
                    "eiaf.dlq.source.partition" to "2",
                    "eiaf.dlq.source.offset" to "41",
                    "eiaf.dlq.attempts" to "1",
                    "eiaf.dlq.failed-at" to "2026-10-08T05:00:00Z",
                )
            headers.getValue(TraceParent.HEADER).split('-')[2] shouldBe spanId
        }

        test("値のないレコード(tombstone)も、そのまま送れる") {
            val mock = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
            val tombstone =
                consumed().let {
                    ConsumerRecord<ByteArray?, ByteArray?>(
                        it.topic(),
                        it.partition(),
                        it.offset(),
                        0L,
                        TimestampType.CREATE_TIME,
                        0,
                        0,
                        it.key(),
                        null,
                        RecordHeaders(),
                        Optional.empty(),
                    )
                }
            DeadLetterPublisher(mock).send(tombstone, DeadLetterReason("UNDECODABLE", "-: 値がない")).ok()
            mock.history().single().value() shouldBe null
        }

        test("送信の失敗は、Kafka の RetriableException なら Retryable") {
            val mock = MockProducer(false, null, ByteArraySerializer(), ByteArraySerializer())
            val result =
                coroutineScope {
                    val sending = async { DeadLetterPublisher(mock).send(consumed(), DeadLetterReason("UNDECODABLE", "-")) }
                    withTimeout(5.seconds) { while (mock.history().isEmpty()) yield() }
                    mock.errorNext(NotLeaderOrFollowerException("leader moved"))
                    sending.await()
                }
            result.err().shouldBeInstanceOf<PublishFailed.Retryable>().topic shouldBe "_cdc.legacy.public.t_juchu.dlq"
        }
    })
