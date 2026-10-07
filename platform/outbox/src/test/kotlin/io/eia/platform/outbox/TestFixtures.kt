package io.eia.platform.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.assertions.fail
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

internal fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

internal val TOPIC = EventTopic.of("test.parcel.shipped.v1")

internal const val SCHEMA =
    """{"type":"record","name":"ParcelShipped","namespace":"io.eia.events.test",""" +
        """"fields":[{"name":"parcelId","type":"string"}]}"""

@Serializable
@SerialName("io.eia.events.test.ParcelShipped")
internal data class ParcelShipped(
    val parcelId: String,
)

internal fun metadata(
    id: Uuid = Uuid.parse("0199b6a0-0000-7000-8000-000000000001"),
    type: String = TOPIC.ceType,
): EventMetadata =
    EventMetadata(
        id = id,
        source = "/test/parcel-service",
        type = type,
        time = Instant.parse("2026-10-07T01:02:03.456789Z"),
        traceParent = TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").ok(),
        correlationId = CorrelationId.parse("corr-1").ok(),
    )

internal fun record(
    id: Uuid = Uuid.parse("0199b6a0-0000-7000-8000-000000000001"),
    topic: EventTopic = TOPIC,
): OutboxRecord = OutboxRecord(topic, "parcel", "p-1", metadata(id, topic.ceType), byteArrayOf(0, 0, 0, 0, 7, 2, 65))

/** contentId 7 で解決済みの serializer(レジストリの代わりに MockEngine で答える)。 */
internal suspend fun resolvedSerializer(): AvroEventSerializer<ParcelShipped> {
    val subject = SchemaSubject(TOPIC.name, SCHEMA)
    val client =
        ApicurioRegistryClient(
            SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
            HttpClient(
                MockEngine {
                    respond(
                        """{"versions":[{"contentId":7,"state":"ENABLED"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )
    val book = SchemaIdBook(listOf(subject), client).also { it.resolve().ok() }
    return AvroEventSerializer(TOPIC, subject, ParcelShipped.serializer(), book)
}
