@file:Suppress("MagicNumber") // テストデータの値・オフセット

package io.eia.platform.messagingkafka

import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CurrentTrace
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val PARTITION = TopicPartition(TOPIC.name, 0)
private const val GROUP = "parcel.tracking"
private const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"

private fun metadata(n: Int): EventMetadata =
    EventMetadata(
        id = Uuid.parse("0199b6a0-0000-7000-8000-00000000000$n"),
        source = "/test/parcel-service",
        type = TOPIC.ceType,
        time = Instant.parse("2026-10-09T01:02:03Z"),
        traceParent = TraceParent.parse("00-$TRACE_ID-00f067aa0ba902b$n-01").ok(),
        correlationId = CorrelationId.parse("corr-$n").ok(),
    )

private fun record(
    offset: Long,
    value: ByteArray?,
    headers: RecordHeaders = RecordHeaders(metadata(offset.toInt() + 1).toHeaders()),
    key: String = "p-$offset",
): ConsumerRecord<ByteArray?, ByteArray?> =
    ConsumerRecord(TOPIC.name, 0, offset, 0L, TimestampType.CREATE_TIME, 0, 0, key.toByteArray(), value, headers, Optional.empty())

/** [handler] の呼び出しを記録する。[results] を順に返し、尽きたら最後の結果を返し続ける。 */
private class ScriptedHandler(
    private vararg val results: Result<Handled, HandlingFailure>,
) : EventHandler<ParcelShipped> {
    val calls = mutableListOf<ConsumedEvent<ParcelShipped>>()
    val traces = mutableListOf<CurrentTrace>()

    override suspend fun handle(event: ConsumedEvent<ParcelShipped>): Result<Handled, HandlingFailure> {
        calls += event
        traces += CurrentTrace.get()
        return results[minOf(calls.size, results.size) - 1]
    }
}

/** 既定と同じ回数(初回 + 3 回)で、待ちだけを短くする。 */
private val FAST_RETRY = EventConsumer.HANDLER_RETRY.copy(initialDelay = 1.milliseconds, maxDelay = 1.milliseconds)

private val PROCESSED: Result<Handled, HandlingFailure> = Result.Ok(Handled.PROCESSED)

class EventConsumerSpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-consumer").ok(),
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans))),
                installLogAppender = false,
            )
        val registry = FakeRegistry(mapOf(SCHEMA_V1 to 7L))
        val payload =
            runBlocking {
                AvroEventSerializer(TOPIC, SUBJECT_V1, ParcelShipped.serializer(), registry.book(SUBJECT_V1)).serialize(SAMPLE).ok()
            }

        beforeTest { spans.reset() }

        class Fixture(
            val consumer: MockConsumer<ByteArray?, ByteArray?>,
            val dlq: MockProducer<ByteArray, ByteArray>,
            val loop: EventConsumer,
        ) {
            fun committed(): OffsetAndMetadata? = consumer.committed(setOf(PARTITION))[PARTITION]

            fun deadLettered(): List<Map<String, String>> =
                dlq.history().map { r ->
                    r.headers().filter { it.key().startsWith("eiaf.dlq.") }.associate { it.key() to String(it.value()) }
                }
        }

        fun fixture(
            handler: EventHandler<ParcelShipped>,
            dlq: MockProducer<ByteArray, ByteArray> = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer()),
            writerSchemas: WriterSchemas = registry.writerSchemas(),
            committer: OffsetCommitter = OffsetCommitter { c, offsets -> c.commitSync(offsets) },
        ): Fixture {
            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            consumer.assign(listOf(PARTITION))
            consumer.updateBeginningOffsets(mapOf(PARTITION to 0L))
            val subscription =
                EventSubscription(TOPIC, AvroEventDeserializer(ParcelShipped.serializer(), writerSchemas), "INT-TEST-001", handler)
            val loop =
                EventConsumer(
                    consumer,
                    GROUP,
                    listOf(subscription),
                    DeadLetterPublisher(dlq),
                    runtime,
                    handlerRetry = FAST_RETRY,
                    committer = committer,
                )
            return Fixture(consumer, dlq, loop)
        }

        test("届いた順に 1 件ずつ処理し、処理を終えたオフセットだけをコミットする。値・キー・メタデータを渡す") {
            val handler = ScriptedHandler(PROCESSED)
            val f = fixture(handler)
            (0L..2L).forEach { f.consumer.addRecord(record(it, payload)) }

            f.loop.pollOnce().shouldBeNull()

            handler.calls.map { it.offset } shouldBe listOf(0L, 1L, 2L)
            handler.calls.first().value shouldBe SAMPLE
            handler.calls.first().key shouldBe "p-0"
            handler.calls.first().metadata shouldBe metadata(1)
            f.committed() shouldBe OffsetAndMetadata(3)
            f.dlq.history().size shouldBe 0
        }

        test("CONSUMER の span はヘッダの traceparent の子で、処理の中では Correlation ID と span を引き継ぐ") {
            val handler = ScriptedHandler(PROCESSED)
            val f = fixture(handler)
            f.consumer.addRecord(record(0, payload))

            f.loop.pollOnce().shouldBeNull()

            val span = spans.finishedSpanItems.single { it.name == "${TOPIC.name} process" }
            span.kind shouldBe SpanKind.CONSUMER
            span.traceId shouldBe TRACE_ID
            span.parentSpanId shouldBe "00f067aa0ba902b1"
            span.attributes.asMap().mapKeys { it.key.key }["messaging.consumer.group.name"] shouldBe GROUP
            span.attributes.asMap().mapKeys { it.key.key }["messaging.message.id"] shouldBe metadata(1).id.toString()
            handler.traces.single().correlationId shouldBe CorrelationId.parse("corr-1").ok()
            handler.traces
                .single()
                .traceParent
                ?.format() shouldBe "00-$TRACE_ID-${span.spanId}-01"
        }

        test("Poison Message(ヘッダの不正・読めない値・tombstone)はリトライせずに DLQ に送り、後続の処理を止めない") {
            val handler = ScriptedHandler(PROCESSED)
            val f = fixture(handler)
            f.consumer.addRecord(record(0, payload, headers = RecordHeaders()))
            f.consumer.addRecord(record(1, byteArrayOf(1, 2, 3)))
            f.consumer.addRecord(record(2, null))
            f.consumer.addRecord(record(3, payload))

            f.loop.pollOnce().shouldBeNull()

            handler.calls.map { it.offset } shouldBe listOf(3L)
            f.committed() shouldBe OffsetAndMetadata(4)
            f.deadLettered().map { it["eiaf.dlq.reason"] } shouldBe
                listOf(EventConsumer.INVALID_HEADERS, EventConsumer.UNDECODABLE, EventConsumer.UNEXPECTED_TOMBSTONE)
            f.deadLettered().map { it["eiaf.dlq.attempts"] } shouldBe listOf("1", "1", "1")
            f.deadLettered().map { it["eiaf.dlq.source.offset"] } shouldBe listOf("0", "1", "2")
            // 値は入れない(項目の名前と規則だけ)
            f.deadLettered().first()["eiaf.dlq.detail"] shouldBe
                "ヘッダが不正です(ce_id: ありません, ce_source: ありません, ce_type: ありません, ce_time: ありません, " +
                "ce_specversion: ありません, traceparent: ありません, correlationid: ありません)"
            f.dlq
                .history()
                .first()
                .value()
                .toList() shouldBe payload.toList()
        }

        test("Rejected はリトライせずに、処理が返したコードを原因として DLQ に送る") {
            val handler = ScriptedHandler(Result.Err(HandlingFailure.Rejected("unknown_sku", "sku: 在庫の台帳にない")))
            val f = fixture(handler)
            f.consumer.addRecord(record(0, payload))

            f.loop.pollOnce().shouldBeNull()

            handler.calls.size shouldBe 1
            f.deadLettered().single()["eiaf.dlq.reason"] shouldBe "UNKNOWN_SKU"
            f.deadLettered().single()["eiaf.dlq.detail"] shouldBe "sku: 在庫の台帳にない"
            f.deadLettered().single()["eiaf.dlq.attempts"] shouldBe "1"
            f.committed() shouldBe OffsetAndMetadata(1)
        }

        test("Transient はその場でリトライし、途中で成功すれば DLQ に送らない") {
            val transient: Result<Handled, HandlingFailure> = Result.Err(HandlingFailure.Transient("conflict", "版の衝突"))
            val handler = ScriptedHandler(transient, transient, PROCESSED)
            val f = fixture(handler)
            f.consumer.addRecord(record(0, payload))

            f.loop.pollOnce().shouldBeNull()

            handler.calls.size shouldBe 3
            f.dlq.history().size shouldBe 0
            f.committed() shouldBe OffsetAndMetadata(1)
        }

        test("Transient が初回 + 3 回続けば DLQ に送り(attempts=4)、次のメッセージに進む") {
            val handler = ScriptedHandler(Result.Err(HandlingFailure.Transient("conflict", "版の衝突")))
            val f = fixture(handler)
            f.consumer.addRecord(record(0, payload))

            f.loop.pollOnce().shouldBeNull()

            handler.calls.size shouldBe 4
            f.deadLettered().single()["eiaf.dlq.reason"] shouldBe "CONFLICT"
            f.deadLettered().single()["eiaf.dlq.attempts"] shouldBe "4"
            f.committed() shouldBe OffsetAndMetadata(1)
        }

        test("処理が想定しない例外を投げても、Transient としてリトライし、尽きたら DLQ に隔離する(メッセージは入れない)") {
            var calls = 0
            val f =
                fixture({ _ ->
                    calls++
                    error("value=secret")
                })
            f.consumer.addRecord(record(0, payload))

            f.loop.pollOnce().shouldBeNull()

            calls shouldBe 4
            f.deadLettered().single()["eiaf.dlq.reason"] shouldBe EventConsumer.UNEXPECTED_EXCEPTION
            f.deadLettered().single()["eiaf.dlq.detail"] shouldBe "処理が例外を投げました(IllegalStateException)"
        }

        test("Unavailable は DLQ に送らず、処理を終えた分までをコミットして、未処理の位置に戻す") {
            val handler =
                ScriptedHandler(PROCESSED, Result.Err(HandlingFailure.Unavailable("inbox_storage_unavailable", "DB に接続できない")))
            val f = fixture(handler)
            (0L..2L).forEach { f.consumer.addRecord(record(it, payload)) }

            f.loop.pollOnce() shouldBe "inbox_storage_unavailable"

            handler.calls.map { it.offset } shouldBe listOf(0L, 1L)
            f.dlq.history().size shouldBe 0
            f.committed() shouldBe OffsetAndMetadata(1)
            f.consumer.position(PARTITION) shouldBe 1L
        }

        test("Schema Registry の一時的な失敗は Unavailable(DLQ に送らない)") {
            val unavailable =
                WriterSchemas(
                    ApicurioRegistryClient(
                        SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
                        HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }),
                    ),
                )
            val handler = ScriptedHandler(PROCESSED)
            val f = fixture(handler, writerSchemas = unavailable)
            f.consumer.addRecord(record(0, payload))

            (f.loop.pollOnce() == null) shouldBe false

            handler.calls.size shouldBe 0
            f.dlq.history().size shouldBe 0
            f.committed().shouldBeNull()
        }

        test("DLQ に送れなければ、オフセットを進めずに読み直す") {
            val dlq = MockProducer(false, null, ByteArraySerializer(), ByteArraySerializer())
            val f = fixture(ScriptedHandler(PROCESSED), dlq = dlq)
            f.consumer.addRecord(record(0, null))
            f.consumer.addRecord(record(1, payload))

            // 送信の結果は送った直後に失敗で返す(認可の拒否)
            val result =
                coroutineScope {
                    val polled = async { f.loop.pollOnce() }
                    while (dlq.history().isEmpty()) yield()
                    dlq.errorNext(AuthorizationException("denied"))
                    polled.await()
                }

            result shouldBe "publish_failed"
            f.committed().shouldBeNull()
            f.consumer.position(PARTITION) shouldBe 0L
        }

        test("コミットの失敗(リバランス)は握りつぶす(新しい割り当て先が送り直し、重複は冪等で吸収する)") {
            val handler = ScriptedHandler(PROCESSED)
            val f = fixture(handler, committer = { _, _ -> throw CommitFailedException() })
            f.consumer.addRecord(record(0, payload))

            f.loop.pollOnce().shouldBeNull()
            handler.calls.size shouldBe 1
        }

        test("ブローカーに届かずコミットがタイムアウトしても(Kafka の停止)、読み取りを止めない。Unavailable の位置の戻しも行う") {
            val timeout =
                OffsetCommitter {
                    _,
                    _,
                    ->
                    throw TimeoutException("Timeout of 60000ms expired before successfully committing offsets")
                }
            val processed = fixture(ScriptedHandler(PROCESSED), committer = timeout)
            processed.consumer.addRecord(record(0, payload))
            processed.loop.pollOnce().shouldBeNull()

            val unavailable =
                fixture(
                    ScriptedHandler(PROCESSED, Result.Err(HandlingFailure.Unavailable("inbox_storage_unavailable", "DB に接続できない"))),
                    committer = timeout,
                )
            (0L..2L).forEach { unavailable.consumer.addRecord(record(it, payload)) }
            unavailable.loop.pollOnce() shouldBe "inbox_storage_unavailable"
            unavailable.consumer.position(PARTITION) shouldBe 1L
        }

        test("購読の誤り(なし・同じトピックを 2 回・Consumer Group の形式)は作るときに拒否する") {
            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            val dlq = DeadLetterPublisher(MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer()))
            val subscription =
                EventSubscription(TOPIC, AvroEventDeserializer(ParcelShipped.serializer(), registry.writerSchemas()), "INT-TEST-001") {
                    PROCESSED
                }
            shouldThrow<IllegalArgumentException> { EventConsumer(consumer, GROUP, emptyList(), dlq, runtime) }
            shouldThrow<IllegalArgumentException> { EventConsumer(consumer, GROUP, listOf(subscription, subscription), dlq, runtime) }
            shouldThrow<IllegalArgumentException> { EventConsumer(consumer, "parcel", listOf(subscription), dlq, runtime) }
            shouldThrow<IllegalArgumentException> {
                EventSubscription(TOPIC, AvroEventDeserializer(ParcelShipped.serializer(), registry.writerSchemas()), " ") { PROCESSED }
            }
        }

        test("Consumer の設定: 自動コミットなし・最初から読む・バイト列のまま受け取る") {
            val properties = EventConsumer.consumerProperties("kafka:9092", GROUP, "parcel-service")
            properties["enable.auto.commit"] shouldBe false
            properties["auto.offset.reset"] shouldBe "earliest"
            properties["group.id"] shouldBe GROUP
            properties.keys shouldContainExactly
                setOf(
                    "bootstrap.servers",
                    "group.id",
                    "client.id",
                    "enable.auto.commit",
                    "auto.offset.reset",
                    "max.poll.records",
                    "key.deserializer",
                    "value.deserializer",
                )
        }

        test("HandlingFailure.of は NonRetryable を Rejected、Retryable を Transient にする") {
            HandlingFailure.of(MalformedEventPayload("x")) shouldBe
                HandlingFailure.Rejected("malformed_event_payload", "イベントのペイロードを読めません(x)")
            HandlingFailure.of(PublishFailed.Retryable("t", "r")) shouldBe HandlingFailure.Transient("publish_failed", "t への送信に失敗しました(r)")
        }
    })
