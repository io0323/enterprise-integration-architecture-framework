package io.eia.platform.messagingkafka

import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaIdsNotResolved
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.shared.kernel.DomainError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory

class AvroEventSerdeSpec :
    FunSpec({
        test("契約のスキーマで Avro にし、先頭に contentId を付ける。Avro の部分は標準の Avro ライブラリで読める") {
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 42))
            val serializer = AvroEventSerializer(TOPIC, SUBJECT_V1, ParcelShipped.serializer(), registry.book(SUBJECT_V1))

            val payload = serializer.serialize(SAMPLE).ok()

            val framed = ApicurioWireFormat.parse(payload).ok()
            framed.contentId shouldBe ContentId(42)
            val schema = Schema.Parser().parse(SCHEMA_V1)
            val record = GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(framed.avroBinary, null))
            record.get("parcelId").toString() shouldBe "p-1"
            // timestamp-micros: マイクロ秒の long(ADR-0012 §3)
            record.get("shippedAt") shouldBe 1_790_902_923_456_789L
            record.get("weightGrams") shouldBe 1250L
            record.get("note").toString() shouldBe "置き配"
        }

        test("自前の deserializer で読み戻せる。書き手のスキーマは contentId で取り、キャッシュする") {
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 42))
            val payload =
                AvroEventSerializer(
                    TOPIC,
                    SUBJECT_V1,
                    ParcelShipped.serializer(),
                    registry.book(SUBJECT_V1),
                ).serialize(SAMPLE).ok()
            val deserializer = AvroEventDeserializer(ParcelShipped.serializer(), registry.writerSchemas())
            val before = registry.requests

            repeat(3) { deserializer.deserialize(payload).ok() shouldBe SAMPLE }
            registry.requests shouldBe before + 1
        }

        test("FULL 互換の範囲の版の違いを読める(v1 で書いたものを v2 の型で・v2 で書いたものを v1 の型で)") {
            val subjectV2 = SchemaSubject("test.parcel.shipped.v1", SCHEMA_V2)
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 1, SCHEMA_V2 to 2))
            val v1Payload =
                AvroEventSerializer(
                    TOPIC,
                    SUBJECT_V1,
                    ParcelShipped.serializer(),
                    registry.book(SUBJECT_V1),
                ).serialize(SAMPLE).ok()
            val v2Value = ParcelShippedV2("p-2", SAMPLE.shippedAt, 10, emptyList(), carrier = "yamato")
            val v2Payload =
                AvroEventSerializer(
                    TOPIC,
                    subjectV2,
                    ParcelShippedV2.serializer(),
                    registry.book(subjectV2),
                ).serialize(v2Value).ok()

            AvroEventDeserializer(ParcelShippedV2.serializer(), registry.writerSchemas()).deserialize(v1Payload).ok() shouldBe
                ParcelShippedV2("p-1", SAMPLE.shippedAt, 1250, listOf("fragile"), note = "置き配", carrier = "unknown")
            AvroEventDeserializer(ParcelShipped.serializer(), registry.writerSchemas()).deserialize(v2Payload).ok() shouldBe
                ParcelShipped("p-2", SAMPLE.shippedAt, 10, emptyList())
        }

        test("ID が未解決なら送らない(Retryable)。レジストリには問い合わせない") {
            val book = SchemaIdBook(listOf(SUBJECT_V1), registryClientThatMustNotBeCalled())
            val error = AvroEventSerializer(TOPIC, SUBJECT_V1, ParcelShipped.serializer(), book).serialize(SAMPLE).err()

            error shouldBe SchemaUnavailable.Temporary(SchemaIdsNotResolved(TOPIC.name))
            error.asDomainError().shouldBeInstanceOf<DomainError.Retryable>()
        }

        test("値がスキーマに合わなければ EventEncodingFailed(値はメッセージに入れない)") {
            // 名前は契約と同じだが、必須の項目(shippedAt など)がない
            @Serializable
            @SerialName("io.eia.events.test.ParcelShipped")
            data class Wrong(
                val parcelId: String,
            )
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 1))
            val error =
                AvroEventSerializer(
                    TOPIC,
                    SUBJECT_V1,
                    Wrong.serializer(),
                    registry.book(SUBJECT_V1),
                ).serialize(Wrong("secret-parcel")).err()

            error.shouldBeInstanceOf<EventEncodingFailed>()
            error.message shouldNotContain "secret-parcel"
        }

        test("読めないペイロード・未知の contentId は受信側のエラーにする") {
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 1))
            val deserializer = AvroEventDeserializer(ParcelShipped.serializer(), registry.writerSchemas())

            deserializer.deserialize(byteArrayOf(1, 2, 3)).err().shouldBeInstanceOf<MalformedEventPayload>()
            deserializer
                .deserialize(
                    ApicurioWireFormat.frame(ContentId(1), byteArrayOf(0x7f)),
                ).err()
                .shouldBeInstanceOf<MalformedEventPayload>()
            deserializer
                .deserialize(
                    ApicurioWireFormat.frame(ContentId(99), byteArrayOf()),
                ).err()
                .shouldBeInstanceOf<SchemaUnavailable.Permanent>()
        }

        test("型の @SerialName が契約の record のフルネームと違えばエンコードできない(名前で対応させるため)") {
            @Serializable
            data class Unnamed(
                val parcelId: String,
                val shippedAt: kotlin.time.Instant,
                val weightGrams: Long,
                val tags: List<String>,
                val note: String? = null,
            )
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 1))
            AvroEventSerializer(TOPIC, SUBJECT_V1, Unnamed.serializer(), registry.book(SUBJECT_V1))
                .serialize(Unnamed("p-1", SAMPLE.shippedAt, 1, emptyList()))
                .err()
                .shouldBeInstanceOf<EventEncodingFailed>()
        }

        test("トピックと subject が食い違う組み合わせは作れない") {
            val registry = FakeRegistry(mapOf(SCHEMA_V1 to 1))
            val book = registry.book(SUBJECT_V1)
            shouldThrow<IllegalArgumentException> {
                AvroEventSerializer(EventTopic.of("test.parcel.lost.v1"), SUBJECT_V1, ParcelShipped.serializer(), book)
            }
        }
    })

/** 呼ばれたら失敗するレジストリ(リクエストの処理中にレジストリへ問い合わせないことの確認)。 */
private fun registryClientThatMustNotBeCalled() =
    ApicurioRegistryClient(
        SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
        HttpClient(MockEngine { error("レジストリに問い合わせました: ${it.url}") }),
    )
