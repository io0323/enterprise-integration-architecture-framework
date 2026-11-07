@file:Suppress("MagicNumber") // wire format のバイト数・待ち時間

package io.eia.tests.e2e.nfr

import io.eia.tests.e2e.E2eEnvironment
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal typealias Event = ConsumerRecord<ByteArray?, ByteArray?>

/**
 * 計測で使う Kafka の読み取り(ホスト用のリスナー。読むだけでオフセットはコミットしない)。
 * 遅延はイベントが運ぶ時刻(`ce_time`・Kafka のレコードの時刻・CDC の `source.committedAt`)の差で出す。
 * どの時刻も Docker の VM の時計なので、ホストとの時計のずれが入らない。
 */
internal object Events {
    private const val KAFKA = "localhost:19092"
    private const val REGISTRY = "http://localhost:19081/apis/registry/v3"
    private val schemas = ConcurrentHashMap<Int, Schema>()
    private const val CLOCK_MARGIN_MILLIS = 5 * 60 * 1000L

    fun header(
        record: Event,
        name: String,
    ): String? =
        record
            .headers()
            .lastHeader(name)
            ?.value()
            ?.toString(Charsets.UTF_8)

    /** `ce_time` のミリ秒(エポック)。 */
    fun ceTime(record: Event): Long? = header(record, "ce_time")?.let { Instant.parse(it).toEpochMilli() }

    /** 契約のペイロード(書き手のスキーマは contentId ごとに 1 回だけ Schema Registry から取る)。 */
    fun decode(value: ByteArray): GenericRecord? {
        val buffer = ByteBuffer.wrap(value)
        if (value.size < 5 || buffer.get() != 0.toByte()) return null
        val contentId = buffer.int
        val schema = schemas.computeIfAbsent(contentId) { Schema.Parser().parse(E2eEnvironment.get("$REGISTRY/ids/contentIds/$it")) }
        return GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(value, 5, value.size - 5, null))
    }

    /**
     * [topics] を時刻 [sinceMillis] の位置から読む。[done] が真になるか、[ReadLimits.quiet] の間なにも届かないか、[ReadLimits.timeout] で終わる。
     * [onRecord] は届いた順に呼ぶ(別のスレッドで受信時刻を記録する用途にも使う)。
     */
    fun read(
        topics: List<String>,
        sinceMillis: Long,
        limits: ReadLimits = ReadLimits(),
        done: () -> Boolean = { false },
        onRecord: (Event) -> Unit,
    ) {
        val properties =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA,
                ConsumerConfig.GROUP_ID_CONFIG to "nfr.measure-${UUID.randomUUID()}",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 2000,
            )
        KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
            val partitions = topics.flatMap { topic -> consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) } }
            consumer.assign(partitions)
            // レコードの時刻は Docker の VM の時計。ホストの時計とずれていても取りこぼさないよう、5 分前から読む(呼び出し側が ID で絞る)
            val offsets = consumer.offsetsForTimes(partitions.associateWith { sinceMillis - CLOCK_MARGIN_MILLIS })
            val ends = consumer.endOffsets(partitions)
            partitions.forEach { partition -> consumer.seek(partition, offsets[partition]?.offset() ?: ends.getValue(partition)) }
            val deadline = System.nanoTime() + limits.timeout.toNanos()
            var lastSeen = System.nanoTime()
            while (System.nanoTime() < deadline && !done() && System.nanoTime() - lastSeen < limits.quiet.toNanos()) {
                val records = consumer.poll(Duration.ofMillis(200))
                if (!records.isEmpty) lastSeen = System.nanoTime()
                records.forEach(onRecord)
            }
        }
    }
}

/** 読み取りの終わり方: 全体の上限 [timeout] と、なにも届かない間の上限 [quiet]。 */
internal data class ReadLimits(
    val timeout: Duration = Duration.ofMinutes(5),
    val quiet: Duration = Duration.ofSeconds(20),
)
