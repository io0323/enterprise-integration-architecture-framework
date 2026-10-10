@file:Suppress("MagicNumber") // wire format のバイト数・待ち時間

package io.eia.tests.e2e

import io.kotest.matchers.shouldBe
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 注文 Saga の E2E(SagaE2E)が使う Kafka と契約のペイロードの部品。公開されたエンドポイント(Kafka のホスト用のリスナーと
 * Schema Registry)だけを使い、サービスのコードには依存しない。
 */
internal object SagaKafka {
    private const val KAFKA = "localhost:19092"
    private const val REGISTRY = "http://localhost:19081/apis/registry/v3"
    private const val MAGIC: Byte = 0

    /** 契約のペイロード(wire format の contentId から、書き手のスキーマを Schema Registry で取って読む)。wire format でなければ null。 */
    fun decode(value: ByteArray): GenericRecord? {
        val buffer = ByteBuffer.wrap(value)
        if (value.size < 5 || buffer.get() != MAGIC) return null
        val schema = Schema.Parser().parse(E2eEnvironment.get("$REGISTRY/ids/contentIds/${buffer.int}"))
        return GenericDatumReader<GenericRecord>(
            schema,
        ).read(null, DecoderFactory.get().binaryDecoder(value.copyOfRange(5, value.size), null))
    }

    /** 契約(Schema Registry に登録された [topic] の最新の版)で [fields] を書いた wire format のペイロード。 */
    fun encode(
        topic: String,
        fields: Map<String, Any>,
    ): ByteArray {
        val metadata =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("$REGISTRY/groups/default/artifacts/$topic-value/versions/branch=latest")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        metadata.statusCode() shouldBe 200
        val contentId =
            Regex(""""contentId"\s*:\s*(\d+)""")
                .find(metadata.body())
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: error("contentId がありません")
        val schema = Schema.Parser().parse(E2eEnvironment.get("$REGISTRY/ids/contentIds/$contentId"))
        val record = GenericData.Record(schema).apply { fields.forEach { (name, value) -> put(name, value) } }
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toInt())
        out.write(ByteBuffer.allocate(4).putInt(contentId).array())
        val encoder = EncoderFactory.get().binaryEncoder(out, null)
        GenericDatumWriter<GenericRecord>(schema).write(record, encoder)
        encoder.flush()
        return out.toByteArray()
    }

    /** [topic] を最初から読み、[match] に合うレコードを [enough] 件そろうか [timeout] まで集める。 */
    fun records(
        topic: String,
        enough: Int = 1,
        timeout: Duration = Duration.ofMinutes(3),
        match: (ConsumerRecord<ByteArray?, ByteArray?>) -> Boolean,
    ): List<ConsumerRecord<ByteArray?, ByteArray?>> {
        val properties =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA,
                ConsumerConfig.GROUP_ID_CONFIG to "e2e.saga-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                // 読むだけなのでオフセットをコミットしない(コミットすると、lag が残ったままのグループが EventConsumerStalled になる)
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            )
        val found = mutableListOf<ConsumerRecord<ByteArray?, ByteArray?>>()
        KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + timeout.toNanos()
            while (found.size < enough && System.nanoTime() < deadline) {
                found += consumer.poll(Duration.ofMillis(500)).filter(match)
            }
        }
        return found
    }

    /** [topic] の契約のペイロードのうち、項目 [field] が [value] のもの(最初の 1 件)。[timeout] まで届かなければ null。 */
    fun payloadWith(
        topic: String,
        field: String,
        value: String,
        timeout: Duration = Duration.ofMinutes(3),
    ): GenericRecord? {
        var payload: GenericRecord? = null
        records(topic, timeout = timeout) { record ->
            val decoded = record.value()?.let(::decode)?.takeIf { it.get(field)?.toString() == value }
            if (decoded != null) payload = decoded
            decoded != null
        }
        return payload
    }

    /** キーが [key](UTF-8)のレコード。 */
    fun keyed(
        topic: String,
        key: String,
        enough: Int = 1,
        timeout: Duration = Duration.ofMinutes(3),
    ): List<ConsumerRecord<ByteArray?, ByteArray?>> = records(topic, enough, timeout) { it.key()?.toString(Charsets.UTF_8) == key }

    fun header(
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        name: String,
    ): String? =
        record
            .headers()
            .lastHeader(name)
            ?.value()
            ?.toString(Charsets.UTF_8)

    fun produce(record: ProducerRecord<ByteArray?, ByteArray?>) {
        val properties = mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA, ProducerConfig.ACKS_CONFIG to "all")
        KafkaProducer(properties, ByteArraySerializer(), ByteArraySerializer()).use { it.send(record).get(30, TimeUnit.SECONDS) }
    }
}
