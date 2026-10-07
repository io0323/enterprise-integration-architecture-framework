package io.eia.platform.outbox

import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.SchemaUnavailable
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-07T03:00:00Z")

class OutboxEventsSpec :
    FunSpec({
        val spans = InMemorySpanExporter.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("test-outbox").ok(),
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans))),
                installLogAppender = false,
            )
        val events = OutboxEvents(runtime, "/test/parcel-service", FixedClock(NOW))
        beforeTest { spans.reset() }

        test("記録を作り、PRODUCER の span(create)の traceparent と呼び出し元の Correlation ID を入れる。ce_id は UUIDv7") {
            val serializer = resolvedSerializer()
            val correlationId = CorrelationId.parse("corr-9").ok()

            val record =
                withContext(ObservabilityContext(correlationId)) { events.create(serializer, "parcel", "p-1", ParcelShipped("p-1")).ok() }

            record.topic shouldBe TOPIC
            record.aggregateType shouldBe "parcel"
            record.aggregateId shouldBe "p-1"
            ApicurioWireFormat
                .parse(record.payload)
                .ok()
                .contentId.value shouldBe 7
            val metadata = record.metadata
            metadata.type shouldBe "test.parcel.shipped"
            metadata.source shouldBe "/test/parcel-service"
            metadata.time shouldBe NOW
            metadata.correlationId shouldBe correlationId
            UUID.fromString(metadata.id.toString()).version() shouldBe 7

            val span = spans.finishedSpanItems.single()
            span.name shouldBe "test.parcel.shipped.v1 create"
            span.kind shouldBe SpanKind.PRODUCER
            metadata.traceParent.traceId.toString() shouldBe span.traceId
            metadata.traceParent.parentId.toString() shouldBe span.spanId
            span.attributes.get(AttributeKey.stringKey("messaging.operation.type")) shouldBe "create"
            span.attributes.get(AttributeKey.stringKey("messaging.message.id")) shouldBe metadata.id.toString()
        }

        test("リクエストの外で呼ばれても、Correlation ID を採番し traceparent を入れる。記録ごとに ID が変わる") {
            val serializer = resolvedSerializer()
            val first = events.create(serializer, "parcel", "p-1", ParcelShipped("p-1")).ok()
            val second = events.create(serializer, "parcel", "p-1", ParcelShipped("p-1")).ok()

            first.metadata.id shouldNotBe second.metadata.id
            first.metadata.correlationId.value
                .isNotBlank() shouldBe true
        }

        test("スキーマ ID が未解決ならエラーにし、span に error.type を残す(記録は作らない)") {
            val subject = SchemaSubject(TOPIC.name, SCHEMA)
            val unresolved =
                AvroEventSerializer(
                    TOPIC,
                    subject,
                    ParcelShipped.serializer(),
                    SchemaIdBook(
                        listOf(subject),
                        ApicurioRegistryClient(
                            SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
                            HttpClient(MockEngine { error("unused") }),
                        ),
                    ),
                )

            events.create(unresolved, "parcel", "p-1", ParcelShipped("p-1")).err().shouldBeInstanceOf<SchemaUnavailable.Temporary>()
            spans.finishedSpanItems
                .single()
                .attributes
                .get(AttributeKey.stringKey("error.type")) shouldBe "schema_ids_not_resolved"
        }
    })
