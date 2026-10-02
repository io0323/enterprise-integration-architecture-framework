@file:Suppress("MagicNumber") // テストデータの値・マイクロ秒の換算・受信の待ち時間

package io.eia.platform.messagingkafka

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer
import io.apicurio.registry.serde.avro.AvroKafkaSerializer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val SCHEMA =
    """
    {
      "type": "record",
      "name": "ParcelShipped",
      "namespace": "io.eia.events.test",
      "fields": [
        { "name": "parcelId", "type": "string" },
        { "name": "shippedAt", "type": { "type": "long", "logicalType": "timestamp-micros" } },
        { "name": "weightGrams", "type": "long" },
        { "name": "tags", "type": { "type": "array", "items": "string" } },
        { "name": "note", "type": ["null", "string"], "default": null }
      ]
    }
    """.trimIndent()

@Serializable
@SerialName("io.eia.events.test.ParcelShipped")
private data class ParcelShipped(
    val parcelId: String,
    val shippedAt: Instant,
    val weightGrams: Long,
    val tags: List<String>,
    val note: String? = null,
)

private val OURS_TOPIC = EventTopic.of("test.parcel.shipped.v1")
private val OFFICIAL_TOPIC = EventTopic.of("test.parcel.shipped.v2")
private val SAMPLE = ParcelShipped("p-1", Instant.parse("2026-10-02T01:02:03.456789Z"), 1250, listOf("fragile"), "置き配")

/**
 * 自前の wire format(ADR-0025 §1)と Apicurio Registry 3 の公式の Serde の相互運用を、実際の Kafka と Registry で双方向に確かめる。
 * - 自前の serializer([EventProducer])で送ったものを、公式の `AvroKafkaDeserializer` で読める
 * - 公式の `AvroKafkaSerializer`(自動登録なし・内容で ID を解決)で送ったものを、自前の [AvroEventDeserializer] で読める
 */
class ApicurioInteropIT :
    FunSpec({
        val registry = ApicurioRegistryContainer().also { it.start() }
        // compose と同じヒープ(KAFKA_HEAP_OPTS)。ほかの統合テストと並列に動くと起動が遅くなるため、待ちは長めにする
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
                .also { it.start() }
        val http = HttpClient(CIO)
        val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http)
        val runtime = Observability.init(ObservabilityConfig.of("interop-it").ok(), TelemetrySinks(), installLogAppender = false)
        Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            admin.createTopics(listOf(NewTopic(OURS_TOPIC.name, 1, 1), NewTopic(OFFICIAL_TOPIC.name, 1, 1))).all().get()
        }
        var oursId: ContentId? = null
        var officialId: ContentId? = null
        beforeSpec {
            // 契約の登録(make schemas と同じ)。整形した JSON のまま登録する
            oursId = client.register(SchemaSubject(OURS_TOPIC.name, SCHEMA)).ok()
            officialId = client.register(SchemaSubject(OFFICIAL_TOPIC.name, SCHEMA)).ok()
        }

        afterSpec {
            runtime.close()
            http.close()
            kafka.stop()
            registry.stop()
        }

        test("自前の serializer で送ったものを、公式の AvroKafkaDeserializer で読める。CloudEvents のヘッダも届く") {
            val subject = SchemaSubject(OURS_TOPIC.name, SCHEMA)
            val serializer =
                AvroEventSerializer(
                    OURS_TOPIC,
                    subject,
                    ParcelShipped.serializer(),
                    SchemaIdBook(listOf(subject), client).also { it.resolve().ok() },
                )
            val settings = KafkaProducerSettings(kafka.bootstrapServers, "interop-it")
            val published =
                KafkaProducer<ByteArray, ByteArray>(settings.toProperties()).use { producer ->
                    EventProducer(producer, runtime, "/test/interop").send(serializer, "p-1", SAMPLE).ok()
                }

            val record =
                consumeOne(
                    kafka.bootstrapServers,
                    OURS_TOPIC.name,
                    AvroKafkaDeserializer<GenericRecord>().apply { configure(mapOf("apicurio.registry.url" to registry.baseUrl), false) },
                )
            val value = record.value()
            value.get("parcelId").toString() shouldBe "p-1"
            value.get("shippedAt") shouldBe SAMPLE.shippedAt.toEpochMicros()
            value.get("weightGrams") shouldBe 1250L
            value.get("tags").let { tags -> (tags as List<*>).map { it.toString() } } shouldBe listOf("fragile")
            value.get("note").toString() shouldBe "置き配"
            String(record.key()) shouldBe "p-1"
            EventMetadata.fromHeaders(record.headers()).ok() shouldBe published.metadata
            published.metadata.type shouldBe "test.parcel.shipped"
            oursId shouldBe ApicurioWireFormat.parse(serializer.serialize(SAMPLE).ok()).ok().contentId
        }

        test("公式の AvroKafkaSerializer(自動登録なし)で送ったものを、自前の deserializer で読める") {
            val schema = Schema.Parser().parse(SCHEMA)
            val record =
                GenericData.Record(schema).apply {
                    put("parcelId", "p-2")
                    put("shippedAt", SAMPLE.shippedAt.toEpochMicros())
                    put("weightGrams", 99L)
                    put("tags", listOf("a", "b"))
                    put("note", null)
                }
            val serializer =
                AvroKafkaSerializer<GenericRecord>().apply {
                    // 既定(ヘッダなし・4 バイトの contentId)のまま。契約は整形して登録しているので、内容の比較は正規化して行う
                    configure(mapOf("apicurio.registry.url" to registry.baseUrl, "apicurio.registry.canonicalize" to true), false)
                }
            KafkaProducer(
                mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
                ByteArraySerializer(),
                serializer,
            ).use { producer -> producer.send(ProducerRecord(OFFICIAL_TOPIC.name, "p-2".toByteArray(), record)).get() }

            val payload = consumeOne(kafka.bootstrapServers, OFFICIAL_TOPIC.name, ByteArrayDeserializer()).value()
            ApicurioWireFormat.parse(payload).ok().contentId shouldBe officialId
            AvroEventDeserializer(ParcelShipped.serializer(), WriterSchemas(client)).deserialize(payload).ok() shouldBe
                ParcelShipped("p-2", SAMPLE.shippedAt, 99, listOf("a", "b"), null)
        }
    })

private fun Instant.toEpochMicros(): Long = epochSeconds * 1_000_000 + nanosecondsOfSecond / 1_000

private fun <V> consumeOne(
    bootstrapServers: String,
    topic: String,
    valueDeserializer: org.apache.kafka.common.serialization.Deserializer<V>,
): ConsumerRecord<ByteArray, V> {
    val properties =
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "interop-it.${Uuid.random()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
    KafkaConsumer(properties, ByteArrayDeserializer(), valueDeserializer).use { consumer ->
        consumer.subscribe(listOf(topic))
        val deadline = System.nanoTime() + Duration.of(30, ChronoUnit.SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).firstOrNull()?.let { return it }
        }
    }
    fail("$topic からメッセージを受け取れませんでした")
}
