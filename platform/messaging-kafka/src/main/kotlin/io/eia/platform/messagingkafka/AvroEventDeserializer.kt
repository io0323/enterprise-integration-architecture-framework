package io.eia.platform.messagingkafka

import com.github.avrokotlin.avro4k.Avro
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import org.apache.avro.AvroRuntimeException
import org.apache.avro.Schema
import org.apache.avro.SchemaParseException
import java.util.concurrent.ConcurrentHashMap

/**
 * [ApicurioWireFormat] のペイロードを読む(受信側。P07 の Consumer の部品)。
 *
 * 先頭の contentId から書き手のスキーマを [writerSchemas] で取り(キャッシュする)、そのスキーマで Avro のバイナリを読んで [T] にする。
 * 書き手のスキーマが読み手([T])の版と違っても、FULL 互換(ADR-0014)の範囲なら読める(項目の対応は名前で取る)。
 */
public class AvroEventDeserializer<T>(
    private val deserializer: DeserializationStrategy<T>,
    private val writerSchemas: WriterSchemas,
    private val avro: Avro = Avro,
) {
    private val parsed = ConcurrentHashMap<ContentId, Schema>()

    @Suppress("ReturnCount") // 形式・書き手のスキーマのそれぞれで、以降を読めないときに返す
    public suspend fun deserialize(payload: ByteArray): Result<T, MessagingError> {
        val framed =
            when (val result = ApicurioWireFormat.parse(payload)) {
                is Result.Ok -> result.value
                is Result.Err -> return result
            }
        val schema =
            when (val result = writerSchema(framed.contentId)) {
                is Result.Ok -> result.value
                is Result.Err -> return result
            }
        return try {
            ok(avro.decodeFromByteArray(schema, deserializer, framed.avroBinary))
        } catch (e: SerializationException) {
            err(MalformedEventPayload("contentId=${framed.contentId} のスキーマで読めません(${e::class.simpleName})"))
        } catch (e: AvroRuntimeException) {
            err(MalformedEventPayload("contentId=${framed.contentId} のスキーマで読めません(${e::class.simpleName})"))
        } catch (e: IllegalArgumentException) {
            err(MalformedEventPayload("contentId=${framed.contentId} のスキーマで読めません(${e::class.simpleName})"))
        }
    }

    private suspend fun writerSchema(contentId: ContentId): Result<Schema, MessagingError> {
        parsed[contentId]?.let { return ok(it) }
        return when (val fetched = writerSchemas.schemaOf(contentId)) {
            is Result.Err -> {
                err(SchemaUnavailable.of(fetched.error))
            }

            is Result.Ok -> {
                try {
                    ok(parsed.computeIfAbsent(contentId) { Schema.Parser().parse(fetched.value) })
                } catch (_: SchemaParseException) {
                    err(MalformedEventPayload("contentId=$contentId のスキーマを解釈できません"))
                }
            }
        }
    }
}
