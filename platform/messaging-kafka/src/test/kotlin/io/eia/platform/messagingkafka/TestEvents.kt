package io.eia.platform.messagingkafka

import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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

/** 契約のスキーマの例(Instant は timestamp-micros、金額は最小通貨単位の long。ADR-0012)。 */
internal val SCHEMA_V1 =
    """
    {"type":"record","name":"ParcelShipped","namespace":"io.eia.events.test","fields":[
      {"name":"parcelId","type":"string"},
      {"name":"shippedAt","type":{"type":"long","logicalType":"timestamp-micros"}},
      {"name":"weightGrams","type":"long"},
      {"name":"tags","type":{"type":"array","items":"string"}},
      {"name":"note","type":["null","string"],"default":null}
    ]}
    """.trimIndent()

/** v1 に default 付きの項目を足した版(FULL 互換。ADR-0014)。 */
internal val SCHEMA_V2 =
    SCHEMA_V1.replace(
        """{"name":"note","type":["null","string"],"default":null}""",
        """{"name":"note","type":["null","string"],"default":null},{"name":"carrier","type":"string","default":"unknown"}""",
    )

internal val SUBJECT_V1 = SchemaSubject(TOPIC.name, SCHEMA_V1)

/** avro4k は record のフルネーム(または alias)で型とスキーマを対応させるため、@SerialName を契約の record のフルネームにする(ADR-0025 §1)。 */
@Serializable
@SerialName("io.eia.events.test.ParcelShipped")
internal data class ParcelShipped(
    val parcelId: String,
    val shippedAt: Instant,
    val weightGrams: Long,
    val tags: List<String>,
    val note: String? = null,
)

@Serializable
@SerialName("io.eia.events.test.ParcelShipped")
internal data class ParcelShippedV2(
    val parcelId: String,
    val shippedAt: Instant,
    val weightGrams: Long,
    val tags: List<String>,
    val note: String? = null,
    val carrier: String = "unknown",
)

internal val SAMPLE = ParcelShipped("p-1", Instant.parse("2026-10-02T01:02:03.456789Z"), 1250, listOf("fragile"), note = "置き配")

/**
 * contentId の割り当て([contents] の内容 → ID)を持つレジストリの代わり。検索は本文の一致で、取得は ID で答える。
 * 実際のレジストリは正規化して比べるが、ここでは同じ文字列だけを一致とみなす。
 */
internal class FakeRegistry(
    private val contents: Map<String, Long>,
) {
    var requests: Int = 0
        private set

    private val client =
        ApicurioRegistryClient(
            SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
            HttpClient(
                MockEngine { request ->
                    requests++
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        request.url.encodedPath.endsWith("/search/versions") -> {
                            val id = contents[String(request.body.toByteArray())]
                            val versions = id?.let { """[{"contentId":$it,"state":"ENABLED"}]""" } ?: "[]"
                            respond("""{"versions":$versions}""", HttpStatusCode.OK, json)
                        }

                        else -> {
                            val id =
                                request.url.encodedPath
                                    .substringAfterLast('/')
                                    .toLong()
                            contents.entries.firstOrNull { it.value == id }?.let { respond(it.key, HttpStatusCode.OK, json) }
                                ?: respond("", HttpStatusCode.NotFound)
                        }
                    }
                },
            ),
        )

    suspend fun book(vararg subjects: SchemaSubject): SchemaIdBook = SchemaIdBook(subjects.toList(), client).also { it.resolve().ok() }

    fun writerSchemas(): WriterSchemas = WriterSchemas(client)
}
