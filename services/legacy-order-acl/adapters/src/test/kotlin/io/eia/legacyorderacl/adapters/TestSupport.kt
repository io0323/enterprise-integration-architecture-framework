package io.eia.legacyorderacl.adapters

import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
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
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.io.ByteArrayOutputStream

internal fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

internal const val RAW_TOPIC = "_cdc.legacy.public.t_juchu"

/** Converter が登録した生の CDC の書き手のスキーマ(参照なし。ローカル基盤の Apicurio から取得したもの)。 */
internal val ENVELOPE_SCHEMA: Schema =
    Schema.Parser().parse(checkNotNull(TestRows::class.java.getResourceAsStream("/debezium/t_juchu-envelope.avsc")))

internal const val ENVELOPE_CONTENT_ID = 31L
internal const val OUTPUT_CONTENT_ID = 7L

/** 書き手のスキーマ(生の CDC)と、出力の契約のスキーマに答えるレジストリの代わり。 */
internal class FakeRegistry {
    private val contents =
        mapOf(
            ENVELOPE_SCHEMA.toString() to ENVELOPE_CONTENT_ID,
            LegacyOrderEventSchemas.legacyOrderChanged.schema to OUTPUT_CONTENT_ID,
        )

    private val client =
        ApicurioRegistryClient(
            SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
            HttpClient(
                MockEngine { request ->
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    if (request.url.encodedPath.endsWith("/search/versions")) {
                        val id = contents[String(request.body.toByteArray())]
                        respond(
                            """{"versions":${id?.let { """[{"contentId":$it,"state":"ENABLED"}]""" } ?: "[]"}}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    } else {
                        val id =
                            request.url.encodedPath
                                .substringAfterLast('/')
                                .toLong()
                        contents.entries.firstOrNull { it.value == id }?.let { respond(it.key, HttpStatusCode.OK, json) }
                            ?: respond("", HttpStatusCode.NotFound)
                    }
                },
            ),
        )

    suspend fun book(): SchemaIdBook = SchemaIdBook(LegacyOrderEventSchemas.subjects, client).also { it.resolve().ok() }

    fun writerSchemas(): WriterSchemas = WriterSchemas(client)
}

/** 生の CDC のレコードを、Converter と同じ形式(Apicurio の wire format + Avro)で作る。 */
internal object TestRows {
    private val rowSchema: Schema =
        ENVELOPE_SCHEMA
            .getField("before")
            .schema()
            .types
            .first { it.type == Schema.Type.RECORD }

    fun row(
        number: String,
        status: String = "1",
        name: String = "山田商事株式会社",
        amount: String = "1200.00",
    ): GenericData.Record =
        GenericData.Record(rowSchema).apply {
            put("col_01", "1")
            put("col_02", number)
            put("col_03", status)
            put("col_04", name.padEnd(40))
            put("col_05", "C0000101")
            put("col_06", amount)
            put("col_07", 1_791_450_000_000_000L)
            put("col_08", 253_402_214_400_000_000L)
        }

    fun envelope(
        op: String,
        before: GenericData.Record? = null,
        after: GenericData.Record? = null,
        lsn: Long? = 26_000_000L,
        snapshot: String? = "false",
    ): GenericData.Record =
        GenericData.Record(ENVELOPE_SCHEMA).apply {
            put(
                "source",
                GenericData.Record(ENVELOPE_SCHEMA.getField("source").schema()).apply {
                    put("version", "3.6.3.Final")
                    put("connector", "postgresql")
                    put("name", "_cdc.legacy")
                    put("ts_ms", 1_791_454_000_123L)
                    put("ts_us", 1_791_454_000_123_456L)
                    put("snapshot", snapshot)
                    put("db", "legacy_sim")
                    put("schema", "public")
                    put("table", "t_juchu")
                    put("lsn", lsn)
                },
            )
            put("before", before)
            put("after", after)
            put("op", op)
        }

    fun bytes(envelope: GenericData.Record): ByteArray =
        ByteArrayOutputStream().use { out ->
            val encoder = EncoderFactory.get().binaryEncoder(out, null)
            GenericDatumWriter<GenericData.Record>(ENVELOPE_SCHEMA).write(envelope, encoder)
            encoder.flush()
            ApicurioWireFormat.frame(ContentId(ENVELOPE_CONTENT_ID), out.toByteArray())
        }

    fun record(
        offset: Long,
        key: String,
        value: ByteArray?,
        partition: Int = 0,
    ): ConsumerRecord<ByteArray?, ByteArray?> = ConsumerRecord(RAW_TOPIC, partition, offset, key.toByteArray(), value)
}
