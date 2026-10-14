package io.eia.batchetl.adapters.kafka

import io.eia.batchetl.application.port.outbound.EventBatch
import io.eia.batchetl.application.port.outbound.OrderEventSource
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.batchetl.domain.OrderEvent
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterReason
import io.eia.platform.messagingkafka.MessagingError
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * 注文のイベント(INT-SALES-002)を、呼んだ時点の末尾まで読む(ADR-0031 §4)。
 *
 * - Consumer Group には参加せず(assign)、位置は分析の DB に保存したもの([SourcePosition]。トピック・パーティションごとの次のオフセット)から読む。
 *   取り込みの正は DB の位置。取り込んだ後に Consumer Group(`batch-etl.order-ingest`)にもコミットする(lag の監視のため。失敗してもよい)。
 * - 保存した位置がトピックの保持期間を過ぎて消えていたら(`OffsetOutOfRange`)、黙って先に進まずに失敗にする
 *   (取りこぼしになるため。`auto.offset.reset=none`。docs/runbooks/batch-rerun.md の保持期間を超えた回復)。
 * - 読めない値(tombstone・形式の誤り・スキーマの不一致)は、受け取ったバイト列のまま DLQ(`{topic}.dlq`)に送って先に進む。
 *   スキーマのレジストリが一時的に使えないときは DLQ に送らず、失敗にする(次の実行でやり直す)。
 *
 * @param consumers 読むたびに作って閉じる([consumerProperties] で作る)
 */
public class KafkaOrderEventSource(
    private val consumers: () -> Consumer<ByteArray?, ByteArray?>,
    writerSchemas: WriterSchemas,
    private val deadLetters: DeadLetterPublisher,
    private val timeout: Duration = 10.minutes,
) : OrderEventSource {
    private val created = AvroEventDeserializer(OrderCreatedV1.serializer(), writerSchemas)
    private val cancelled = AvroEventDeserializer(OrderCancelledV1.serializer(), writerSchemas)
    private val statusChanged = AvroEventDeserializer(OrderStatusChangedV1.serializer(), writerSchemas)

    override suspend fun drain(
        start: SourcePosition?,
        load: suspend (EventBatch) -> Result<Unit, DomainError>,
    ): Result<Unit, DomainError> =
        try {
            withContext(Dispatchers.IO) { consumers().use { consumer -> drain(consumer, start, load) } }
        } catch (e: OffsetOutOfRangeException) {
            err(UnexpectedError("取り込んだ位置のイベントが保持期間を過ぎて消えています(${e.partitions().size} パーティション)", e))
        } catch (e: KafkaException) {
            err(UnavailableError("Kafka から読めません(${e::class.simpleName})"))
        }

    @Suppress("ReturnCount") // 時間切れ・読めない・取り込めないときは、そこで返す
    private suspend fun drain(
        consumer: Consumer<ByteArray?, ByteArray?>,
        start: SourcePosition?,
        load: suspend (EventBatch) -> Result<Unit, DomainError>,
    ): Result<Unit, DomainError> {
        val partitions = TOPICS.flatMap { topic -> consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) } }
        consumer.assign(partitions)
        val ends = consumer.endOffsets(partitions)
        val positions = start?.let(::decode).orEmpty()
        partitions.forEach { partition ->
            positions[partition]?.let { consumer.seek(partition, it) } ?: consumer.seekToBeginning(listOf(partition))
        }
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var loadedUpTo = partitions.associateWith { consumer.position(it) }
        while (partitions.any { consumer.position(it) < ends.getValue(it) }) {
            if (deadline.hasPassedNow()) return err(UnavailableError("注文のイベントを $timeout で末尾まで読めません"))
            val records =
                consumer.poll(POLL.toJavaDuration()).filter {
                    it.offset() <
                        ends.getValue(TopicPartition(it.topic(), it.partition()))
                }
            val events = mutableListOf<OrderEvent>()
            var dead = 0
            for (record in records) {
                when (val decoded = decode(record)) {
                    is Decoded.Event -> events += decoded.event
                    is Decoded.Dead -> dead++
                    is Decoded.Failed -> return err(decoded.error)
                }
            }
            // 末尾より先は次の実行で読む(この実行の位置は末尾で止める)
            val next = partitions.associateWith { minOf(consumer.position(it), ends.getValue(it)) }
            // 何も読まず、位置も進まなかった(まだ届いていない)なら、保存しない
            if (records.isEmpty() && next == loadedUpTo) continue
            val loaded = load(EventBatch(events, encode(next), dead))
            if (loaded is Result.Err) return loaded
            commitToGroup(consumer, next)
            loadedUpTo = next
        }
        return ok(Unit)
    }

    private sealed interface Decoded {
        data class Event(
            val event: OrderEvent,
        ) : Decoded

        data object Dead : Decoded

        data class Failed(
            val error: DomainError,
        ) : Decoded
    }

    private suspend fun decode(record: ConsumerRecord<ByteArray?, ByteArray?>): Decoded {
        val value = record.value() ?: return deadLetter(record, DeadLetterReason("UNEXPECTED_TOMBSTONE", "値がありません"))
        val event: Result<OrderEvent, MessagingError> =
            when (record.topic()) {
                ORDER_CREATED -> created.deserialize(value).map { it.toDomain() }
                ORDER_CANCELLED -> cancelled.deserialize(value).map { it.toDomain() }
                else -> statusChanged.deserialize(value).map { it.toDomain() }
            }
        return when (event) {
            is Result.Ok -> {
                Decoded.Event(event.value)
            }

            is Result.Err -> {
                when (val error = event.error.asDomainError()) {
                    // レジストリが一時的に使えない: DLQ に送らず、次の実行で読み直す
                    is DomainError.Retryable -> Decoded.Failed(error)

                    is DomainError.NonRetryable -> deadLetter(record, DeadLetterReason("UNDECODABLE", error.code))
                }
            }
        }
    }

    private suspend fun deadLetter(
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        reason: DeadLetterReason,
    ): Decoded =
        when (val sent = deadLetters.send(record, reason)) {
            is Result.Ok -> {
                logger.warn("{} の {}/{} を DLQ に送りました({})", record.topic(), record.partition(), record.offset(), reason.code)
                Decoded.Dead
            }

            // DLQ に送れなければ、位置を進めない
            is Result.Err -> {
                Decoded.Failed(UnavailableError("DLQ に送れません(${sent.error.code})"))
            }
        }

    private fun commitToGroup(
        consumer: Consumer<ByteArray?, ByteArray?>,
        next: Map<TopicPartition, Long>,
    ) {
        try {
            consumer.commitSync(next.mapValues { OffsetAndMetadata(it.value) })
        } catch (e: KafkaException) {
            // 取り込みの正は DB の位置。Consumer Group のコミットは lag の監視のためだけなので、失敗しても止めない
            logger.warn("{} にオフセットをコミットできません({})", GROUP_ID, e::class.simpleName)
        }
    }

    public companion object {
        public const val GROUP_ID: String = "batch-etl.order-ingest"
        public const val ORDER_CREATED: String = "sales.order.created.v1"
        public const val ORDER_CANCELLED: String = "sales.order.cancelled.v1"
        public const val ORDER_STATUS_CHANGED: String = "sales.order.status-changed.v1"
        public val TOPICS: List<String> = listOf(ORDER_CREATED, ORDER_CANCELLED, ORDER_STATUS_CHANGED)
        private const val MAX_POLL_RECORDS = 500
        private val POLL = 500.milliseconds
        private val logger = LoggerFactory.getLogger(KafkaOrderEventSource::class.java)
        private val POSITIONS = MapSerializer(String.serializer(), Long.serializer())

        /** 読む Consumer の設定。Consumer Group の名前は lag の監視のため。位置がなければ失敗にする(`auto.offset.reset=none`)。 */
        public fun consumerProperties(bootstrapServers: String): Map<String, Any> =
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to GROUP_ID,
                ConsumerConfig.CLIENT_ID_CONFIG to "batch-etl-ingest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "none",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to MAX_POLL_RECORDS,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            )

        /** 位置の形: `{"sales.order.created.v1:0": 42, ...}`(パーティションごとの次のオフセット)。 */
        internal fun encode(positions: Map<TopicPartition, Long>): SourcePosition =
            SourcePosition(Json.encodeToString(POSITIONS, positions.mapKeys { "${it.key.topic()}:${it.key.partition()}" }.toSortedMap()))

        internal fun decode(position: SourcePosition): Map<TopicPartition, Long> =
            Json.decodeFromString(POSITIONS, position.value).mapKeys { (key, _) ->
                TopicPartition(key.substringBeforeLast(':'), key.substringAfterLast(':').toInt())
            }
    }
}
