package io.eia.platform.messagingkafka

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * DLQ(`{topic}.dlq`)のメッセージを、原因を除いた後に元のトピックへ戻す(Framework 6.5・13.1 の Replay。ADR-0028 §6)。
 * 手順は docs/runbooks/event-dlq-replay.md。
 *
 * - 読む範囲は、始めた時点の DLQ の末尾まで(実行中に DLQ に入ったメッセージは対象にしない)。Consumer Group を使わず、オフセットもコミットしない。
 * - 既定は dry-run(対象を数えて一覧にするだけで送らない)。送るのは [ReplayRequest.execute] が true のときだけ。件数の上限([ReplayRequest.limit])は必須。
 * - 戻すメッセージは、キー・値・`ce_id` を含むヘッダを DLQ のまま使い、`eiaf.dlq.*` を外して `eiaf.replay.*` を加える。
 *   `ce_id` が変わらないので、同じメッセージを 2 回戻しても、受信側の冪等消費(platform/inbox)で 1 回だけ処理される。
 * - 生の CDC の DLQ(`_cdc.*`)は戻さない。回復は signal 表による部分の再同期で行う(ADR-0026 §7。docs/runbooks/cdc-resync.md)。
 * - ログ・結果に値は入れない(キーは出す。キーは集約の ID で、イベントの値を含まない)。
 *
 * [consumer] と [producer] は [connect] で作る(閉じるのは [close])。
 */
public class DeadLetterReplayer(
    private val consumer: Consumer<ByteArray?, ByteArray?>,
    private val producer: Producer<ByteArray?, ByteArray?>,
    private val clock: Clock = Clock.System,
    private val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
    private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) : Closeable {
    @Suppress("ReturnCount") // 依頼の検査・読み取りの失敗のそれぞれで、送る前に返す
    public suspend fun replay(request: ReplayRequest): Result<ReplayReport, ReplayError> {
        refusal(request.deadLetterTopic)?.let { return err(it) }
        val partitions =
            try {
                consumer.partitionsFor(request.deadLetterTopic).orEmpty().map { TopicPartition(request.deadLetterTopic, it.partition()) }
            } catch (e: KafkaException) {
                return err(ReplayError.Unavailable("${request.deadLetterTopic} の情報を取得できません(${e::class.simpleName})"))
            }
        if (partitions.isEmpty()) return err(ReplayError.Refused("${request.deadLetterTopic} がありません"))
        val candidates =
            when (val read = read(partitions, request)) {
                is Result.Ok -> read.value
                is Result.Err -> return read
            }
        val selected = candidates.matched.take(request.limit)
        val outcomes = if (request.execute) selected.map { send(it, request.sourceTopic) } else selected.map { ReplayOutcome.PLANNED }
        return ok(
            ReplayReport(
                deadLetterTopic = request.deadLetterTopic,
                sourceTopic = request.sourceTopic,
                executed = request.execute,
                scanned = candidates.scanned,
                matched = candidates.matched.size,
                entries = selected.map { it.entry }.zip(outcomes) { entry, outcome -> entry.copy(outcome = outcome) },
            ),
        )
    }

    override fun close() {
        consumer.close()
        producer.close()
    }

    private class Candidate(
        val record: ConsumerRecord<ByteArray?, ByteArray?>,
        val entry: ReplayEntry,
    )

    private class Read(
        val scanned: Int,
        val matched: List<Candidate>,
    )

    /** 始めた時点の末尾まで読み、条件に合うものを古い順(パーティションごと)に集める。 */
    private fun read(
        partitions: List<TopicPartition>,
        request: ReplayRequest,
    ): Result<Read, ReplayError> =
        try {
            val selected = partitions.filter { request.filter.partition == null || it.partition() == request.filter.partition }
            consumer.assign(selected)
            val end = consumer.endOffsets(selected)
            val begin = consumer.beginningOffsets(selected)
            selected.forEach { p -> consumer.seek(p, maxOf(begin.getValue(p), request.filter.fromOffset ?: 0L)) }
            val records = readUntil(end, request.deadLetterTopic)
            records?.let { read ->
                val matched = read.map { Candidate(it, entryOf(it)) }.filter { request.filter.matches(it.entry) }
                ok(Read(read.size, matched.sortedWith(compareBy({ it.entry.partition }, { it.entry.offset }))))
            } ?: err(ReplayError.Unavailable("${request.deadLetterTopic} を $readTimeout の間に末尾まで読めません"))
        } catch (e: KafkaException) {
            err(ReplayError.Unavailable("${request.deadLetterTopic} を読めません(${e::class.simpleName})"))
        }

    /** 各パーティションを [end] の手前まで読む。[readTimeout] を超えたら null。 */
    private fun readUntil(
        end: Map<TopicPartition, Long>,
        topic: String,
    ): List<ConsumerRecord<ByteArray?, ByteArray?>>? {
        val started = TimeSource.Monotonic.markNow()
        val read = mutableListOf<ConsumerRecord<ByteArray?, ByteArray?>>()
        while (end.any { (p, last) -> consumer.position(p) < last }) {
            if (started.elapsedNow() > readTimeout) return null
            read += consumer.poll(pollTimeout.toJavaDuration()).filter { it.offset() < end.getValue(TopicPartition(topic, it.partition())) }
        }
        return read
    }

    private suspend fun send(
        candidate: Candidate,
        sourceTopic: String,
    ): ReplayOutcome {
        val record = candidate.record
        // 元のトピックと違う DLQ のレコード(手で入れたものなど)は戻さない
        if (candidate.entry.sourceTopic != sourceTopic) return ReplayOutcome.SKIPPED_SOURCE_MISMATCH
        val headers =
            record.headers().filterNot { it.key().startsWith(DeadLetterHeaders.PREFIX) || it.key().startsWith(ReplayHeaders.PREFIX) } +
                listOf(
                    header(ReplayHeaders.DEAD_LETTER_PARTITION, record.partition().toString()),
                    header(ReplayHeaders.DEAD_LETTER_OFFSET, record.offset().toString()),
                    header(ReplayHeaders.REASON, candidate.entry.reason.orEmpty()),
                    header(ReplayHeaders.REPLAYED_AT, clock.now().toString()),
                )
        // パーティションは指定しない(キーで決まる。元のメッセージと同じパーティションに入る)
        val replayed = ProducerRecord(sourceTopic, null, record.key(), record.value(), headers)
        return try {
            suspendCancellableCoroutine { continuation ->
                producer.send(replayed) { _, exception ->
                    continuation.resume(if (exception == null) ReplayOutcome.REPLAYED else ReplayOutcome.FAILED)
                }
            }
        } catch (_: KafkaException) {
            ReplayOutcome.FAILED
        }
    }

    public companion object {
        private val DEFAULT_POLL_TIMEOUT = 500.milliseconds
        private val DEFAULT_READ_TIMEOUT = 60.seconds
        private const val RAW_CDC_PREFIX = "_cdc."

        /** 戻してはならない DLQ。理由を返す(戻してよければ null)。 */
        internal fun refusal(deadLetterTopic: String): ReplayError.Refused? =
            when {
                !deadLetterTopic.endsWith(DeadLetterPublisher.SUFFIX) || deadLetterTopic == DeadLetterPublisher.SUFFIX -> {
                    ReplayError.Refused("$deadLetterTopic は DLQ ではありません({topic}.dlq を指定してください)")
                }

                deadLetterTopic.startsWith(RAW_CDC_PREFIX) -> {
                    ReplayError.Refused(
                        "生の CDC の DLQ は戻しません。signal 表の部分の再同期で回復してください(ADR-0026 §7・docs/runbooks/cdc-resync.md)",
                    )
                }

                else -> {
                    null
                }
            }

        /**
         * Kafka に接続して作る。DLQ は Consumer Group なしで読み(オフセットをコミットしない)、戻す送信は冪等・acks=all。
         *
         * @param clientId Kafka の `client.id`(例 `dlq-replay`)
         */
        public fun connect(
            bootstrapServers: String,
            clientId: String,
        ): DeadLetterReplayer {
            val consumer =
                KafkaConsumer<ByteArray?, ByteArray?>(
                    mapOf<String, Any>(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                        ConsumerConfig.CLIENT_ID_CONFIG to clientId,
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                    ),
                )
            val producer = KafkaProducer<ByteArray?, ByteArray?>(KafkaProducerSettings(bootstrapServers, clientId).toProperties())
            return DeadLetterReplayer(consumer, producer)
        }

        private fun entryOf(record: ConsumerRecord<ByteArray?, ByteArray?>): ReplayEntry {
            fun text(name: String): String? =
                record
                    .headers()
                    .lastHeader(name)
                    ?.value()
                    ?.toString(Charsets.UTF_8)
            return ReplayEntry(
                partition = record.partition(),
                offset = record.offset(),
                key = record.key()?.toString(Charsets.UTF_8),
                ceId = text(EventMetadata.CE_ID),
                ceType = text(EventMetadata.CE_TYPE),
                reason = text(DeadLetterHeaders.REASON),
                detail = text(DeadLetterHeaders.DETAIL),
                sourceTopic = text(DeadLetterHeaders.SOURCE_TOPIC),
                sourceOffset = text(DeadLetterHeaders.SOURCE_OFFSET)?.toLongOrNull(),
                failedAt = text(DeadLetterHeaders.FAILED_AT)?.let(::parseInstant),
                replayedBefore = record.headers().lastHeader(ReplayHeaders.REPLAYED_AT) != null,
            )
        }

        private fun header(
            key: String,
            value: String,
        ): Header = RecordHeader(key, value.toByteArray(Charsets.UTF_8))

        /** ISO 8601 の時刻。形式が不正なら null(手で入れた DLQ のレコードなど)。 */
        public fun parseInstant(value: String): Instant? =
            try {
                Instant.parse(value)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

/**
 * Replay の依頼。
 *
 * @property deadLetterTopic 読む DLQ(`{topic}.dlq`)
 * @property limit 戻す件数の上限(1〜[MAX_LIMIT])。条件に合うものが多ければ、古い順(パーティションごと)に上限まで
 * @property execute false なら dry-run(送らない)
 */
public data class ReplayRequest(
    public val deadLetterTopic: String,
    public val filter: ReplayFilter,
    public val limit: Int,
    public val execute: Boolean,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit は 1〜$MAX_LIMIT にしてください: $limit" }
    }

    /** 戻す先(DLQ の名前から `.dlq` を除いたもの)。 */
    public val sourceTopic: String get() = deadLetterTopic.removeSuffix(DeadLetterPublisher.SUFFIX)

    public companion object {
        /** 1 回で戻せる件数の上限。それより多いときは、条件で絞るか、結果を確かめながら繰り返す(Runbook)。 */
        public const val MAX_LIMIT: Int = 1000
    }
}

/**
 * Replay の対象の条件。null の項目は条件にしない。すべての条件を満たすものが対象。
 *
 * @property reason DLQ の `eiaf.dlq.reason`(例 `UNKNOWN_SKU`)
 * @property ceType `ce_type`
 * @property key Kafka のキー(UTF-8)
 * @property partition DLQ のパーティション
 * @property fromOffset DLQ のオフセットの下限(含む)。[partition] と組み合わせて使う
 * @property toOffset DLQ のオフセットの上限(含む)
 * @property failedFrom DLQ に入った時刻の下限(含む。`eiaf.dlq.failed-at`)
 * @property failedTo DLQ に入った時刻の上限(含まない)
 * @property includeReplayed false なら、一度戻して再び DLQ に入ったもの(`eiaf.replay.*` が付いたもの)を除く
 */
public data class ReplayFilter(
    public val reason: String? = null,
    public val ceType: String? = null,
    public val key: String? = null,
    public val partition: Int? = null,
    public val fromOffset: Long? = null,
    public val toOffset: Long? = null,
    public val failedFrom: Instant? = null,
    public val failedTo: Instant? = null,
    public val includeReplayed: Boolean = true,
) {
    init {
        require(fromOffset == null || fromOffset >= 0) { "fromOffset は 0 以上にしてください" }
        require(fromOffset == null || toOffset == null || fromOffset <= toOffset) { "fromOffset は toOffset 以下にしてください" }
        require(failedFrom == null || failedTo == null || failedFrom < failedTo) { "failedFrom は failedTo より前にしてください" }
    }

    @Suppress("CyclomaticComplexMethod") // 条件の項目ごとの比較だけ
    internal fun matches(entry: ReplayEntry): Boolean =
        (reason == null || entry.reason == reason) &&
            (ceType == null || entry.ceType == ceType) &&
            (key == null || entry.key == key) &&
            (partition == null || entry.partition == partition) &&
            (fromOffset == null || entry.offset >= fromOffset) &&
            (toOffset == null || entry.offset <= toOffset) &&
            (failedFrom == null || (entry.failedAt != null && entry.failedAt >= failedFrom)) &&
            (failedTo == null || (entry.failedAt != null && entry.failedAt < failedTo)) &&
            (includeReplayed || !entry.replayedBefore)
}

/** DLQ の 1 件(値は含まない)。 */
public data class ReplayEntry(
    public val partition: Int,
    public val offset: Long,
    public val key: String?,
    public val ceId: String?,
    public val ceType: String?,
    public val reason: String?,
    public val detail: String?,
    public val sourceTopic: String?,
    public val sourceOffset: Long?,
    public val failedAt: Instant?,
    public val replayedBefore: Boolean,
    public val outcome: ReplayOutcome = ReplayOutcome.PLANNED,
)

public enum class ReplayOutcome {
    /** dry-run(送っていない)。 */
    PLANNED,

    /** 元のトピックに送った。 */
    REPLAYED,

    /** 元のトピックの記録(`eiaf.dlq.source.topic`)が DLQ の名前と合わないため、送らなかった。 */
    SKIPPED_SOURCE_MISMATCH,

    /** 送信に失敗した(もう一度実行してよい。受信側は冪等)。 */
    FAILED,
}

/**
 * Replay の結果。
 *
 * @property scanned 読んだ件数(始めた時点の末尾まで)
 * @property matched 条件に合った件数(上限を超えた分も含む)
 * @property entries 上限までの対象と、それぞれの結果
 */
public data class ReplayReport(
    public val deadLetterTopic: String,
    public val sourceTopic: String,
    public val executed: Boolean,
    public val scanned: Int,
    public val matched: Int,
    public val entries: List<ReplayEntry>,
) {
    /** 条件に合ったが、上限のため対象にしなかった件数。 */
    public val truncated: Int get() = matched - entries.size

    public fun count(outcome: ReplayOutcome): Int = entries.count { it.outcome == outcome }
}

/** Replay の失敗。メッセージに値は入れない。 */
public sealed interface ReplayError {
    public val message: String

    /** 戻してはならない DLQ・存在しない DLQ。 */
    public data class Refused(
        override val message: String,
    ) : ReplayError

    /** Kafka に接続できない・読み終えられない。 */
    public data class Unavailable(
        override val message: String,
    ) : ReplayError
}

/** 戻したメッセージに付けるヘッダ(INTEGRATION_STANDARDS §2。ADR-0028 §6)。値は UTF-8 の文字列。 */
public object ReplayHeaders {
    public const val PREFIX: String = "eiaf.replay."

    /** 戻した元の DLQ の位置。 */
    public const val DEAD_LETTER_PARTITION: String = "${PREFIX}dlq.partition"
    public const val DEAD_LETTER_OFFSET: String = "${PREFIX}dlq.offset"

    /** DLQ に入ったときの原因(`eiaf.dlq.reason`)。 */
    public const val REASON: String = "${PREFIX}reason"

    /** 戻した時刻(ISO 8601 の UTC)。 */
    public const val REPLAYED_AT: String = "${PREFIX}replayed-at"
}
