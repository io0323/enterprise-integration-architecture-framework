package io.eia.platform.messagingkafka

import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.serialization.ByteArraySerializer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-02T03:00:00Z")

class EventProducerSpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-producer").ok(),
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans))),
                installLogAppender = false,
            )

        beforeTest { spans.reset() }

        suspend fun fixture(
            autoComplete: Boolean = true,
        ): Pair<MockProducer<ByteArray, ByteArray>, Pair<EventProducer, AvroEventSerializer<ParcelShipped>>> {
            val mock = MockProducer(autoComplete, null, ByteArraySerializer(), ByteArraySerializer())
            val serializer =
                AvroEventSerializer(TOPIC, SUBJECT_V1, ParcelShipped.serializer(), FakeRegistry(mapOf(SCHEMA_V1 to 7)).book(SUBJECT_V1))
            return mock to (EventProducer(mock, runtime, "/test/parcel-service", FixedClock(NOW)) to serializer)
        }

        test("キーと値を送り、CloudEvents のヘッダに PRODUCER の span の traceparent と呼び出し元の Correlation ID を入れる") {
            val (mock, pair) = fixture()
            val (producer, serializer) = pair
            val correlationId = CorrelationId.parse("corr-1").ok()

            val published = withContext(ObservabilityContext(correlationId)) { producer.send(serializer, "p-1", SAMPLE).ok() }

            val record = mock.history().single()
            record.topic() shouldBe "test.parcel.shipped.v1"
            String(record.key()) shouldBe "p-1"
            ApicurioWireFormat
                .parse(record.value())
                .ok()
                .contentId.value shouldBe 7
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata shouldBe published.metadata
            metadata.source shouldBe "/test/parcel-service"
            metadata.type shouldBe "test.parcel.shipped"
            metadata.time shouldBe NOW
            metadata.correlationId shouldBe correlationId

            val span = spans.finishedSpanItems.single()
            span.name shouldBe "test.parcel.shipped.v1 publish"
            span.kind shouldBe SpanKind.PRODUCER
            metadata.traceParent.traceId.toString() shouldBe span.traceId
            metadata.traceParent.parentId.toString() shouldBe span.spanId
            span.attributes.get(AttributeKey.stringKey("messaging.destination.name")) shouldBe "test.parcel.shipped.v1"
            span.attributes.get(AttributeKey.stringKey("messaging.message.id")) shouldBe metadata.id.toString()
        }

        test("イベントごとに ce_id を変える") {
            val (mock, pair) = fixture()
            val (producer, serializer) = pair
            producer.send(serializer, "p-1", SAMPLE).ok()
            producer.send(serializer, "p-1", SAMPLE).ok()

            mock.history().map { EventMetadata.fromHeaders(it.headers()).ok().id }.distinct() shouldHaveSize 2
        }

        test("送信の完了(acks)まで待ち、Kafka の RetriableException は Retryable、それ以外は NonRetryable にする") {
            val (mock, pair) = fixture(autoComplete = false)
            val (producer, serializer) = pair

            val retryable =
                coroutineScope {
                    val sending = async { producer.send(serializer, "p-1", SAMPLE) }
                    withTimeout(5.seconds) { while (mock.history().isEmpty()) yield() }
                    mock.errorNext(NotLeaderOrFollowerException("leader moved"))
                    sending.await()
                }
            retryable.err() shouldBe PublishFailed.Retryable("test.parcel.shipped.v1", "NotLeaderOrFollowerException")

            val permanent =
                coroutineScope {
                    val sending = async { producer.send(serializer, "p-1", SAMPLE) }
                    withTimeout(5.seconds) { while (mock.history().size < 2) yield() }
                    mock.errorNext(RecordTooLargeException("too large"))
                    sending.await()
                }
            permanent.err().shouldBeInstanceOf<PublishFailed.NonRetryable>()
            spans.finishedSpanItems.map { it.attributes.get(AttributeKey.stringKey("error.type")) } shouldBe
                listOf("publish_failed", "publish_failed")
        }

        test("tombstone: 値のないレコードを、キーと同じヘッダ(CloudEvents・traceparent・correlationid)で送る") {
            val (mock, pair) = fixture()
            val (producer, _) = pair
            val correlationId = CorrelationId.parse("corr-2").ok()

            val published = withContext(ObservabilityContext(correlationId)) { producer.sendTombstone(TOPIC, "p-9").ok() }

            val record = mock.history().single()
            record.topic() shouldBe "test.parcel.shipped.v1"
            String(record.key()) shouldBe "p-9"
            record.value() shouldBe null
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata shouldBe published.metadata
            metadata.type shouldBe "test.parcel.shipped"
            metadata.correlationId shouldBe correlationId
            val span = spans.finishedSpanItems.single()
            span.kind shouldBe SpanKind.PRODUCER
            metadata.traceParent.parentId.toString() shouldBe span.spanId
        }

        test("エンコードできなければ送らない") {
            val (mock, pair) = fixture()
            val (producer, _) = pair
            // ID を解決していない(起動直後でレジストリが止まっている)
            val unresolved =
                AvroEventSerializer(TOPIC, SUBJECT_V1, ParcelShipped.serializer(), SchemaIdBook(listOf(SUBJECT_V1), unusedClient()))

            producer.send(unresolved, "p-1", SAMPLE).err().shouldBeInstanceOf<SchemaUnavailable.Temporary>()
            mock.history().shouldBeEmpty()
        }
    })

private fun unusedClient() =
    ApicurioRegistryClient(
        SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
        HttpClient(MockEngine { error("レジストリに問い合わせました: ${it.url}") }),
    )
