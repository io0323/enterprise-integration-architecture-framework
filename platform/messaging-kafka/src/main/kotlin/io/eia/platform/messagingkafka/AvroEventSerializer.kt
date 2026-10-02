package io.eia.platform.messagingkafka

import com.github.avrokotlin.avro4k.Avro
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import org.apache.avro.AvroRuntimeException
import org.apache.avro.Schema

/**
 * 1 つのトピックの値を、契約のスキーマで Avro のバイナリにし、[ApicurioWireFormat] で contentId を付ける(ADR-0025 §1)。
 *
 * - 書き手のスキーマは [subject] の契約のスキーマ(`contracts/avro`)。Kotlin のクラスから生成したスキーマは使わない
 *   (契約の項目名・型で書くため。Kotlin のクラスと契約のずれは、エンコードの失敗か contract-check の一致検査で見つかる)。
 * - contentId は [ids] から取る。レジストリには問い合わせない(起動時に解決したもの。ADR-0025 §3)。
 * - 結果のバイト列は、Kafka に直接送る([EventProducer])ことも、Outbox の `payload` に保存する(ADR-0007)こともできる。
 */
public class AvroEventSerializer<T>(
    public val topic: EventTopic,
    public val subject: SchemaSubject,
    private val serializer: SerializationStrategy<T>,
    private val ids: SchemaIdBook,
    private val avro: Avro = Avro,
) {
    init {
        require(subject.topic == topic.name) { "subject のトピック(${subject.topic})と $topic が違います" }
    }

    private val writerSchema: Schema = Schema.Parser().parse(subject.schema)

    public fun serialize(value: T): Result<ByteArray, MessagingError> {
        val contentId =
            when (val found = ids.contentIdOf(subject)) {
                is Result.Ok -> found.value
                is Result.Err -> return err(SchemaUnavailable.of(found.error))
            }
        return try {
            ok(ApicurioWireFormat.frame(contentId, avro.encodeToByteArray(writerSchema, serializer, value)))
        } catch (e: SerializationException) {
            err(EventEncodingFailed(topic.name, e::class.simpleName ?: "SerializationException"))
        } catch (e: AvroRuntimeException) {
            err(EventEncodingFailed(topic.name, e::class.simpleName ?: "AvroRuntimeException"))
        }
    }
}
