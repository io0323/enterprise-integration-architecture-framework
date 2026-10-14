package io.eia.batchetl.adapters.kafka

import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig
import java.util.concurrent.ExecutionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * 取り込みの遅れ(保持期間に対して)。ADR-0031 §7。
 *
 * @property oldestPendingAge まだ取り込んでいないイベントのうち最も古いものの経過時間。取り込み残しがなければ 0
 * @property retention 注文のイベントのトピックの保持期間(最も短いもの)
 * @property lost 保持期間を過ぎて消えたため、取り込めなくなったイベントの件数(パーティションごと)。なければ空
 */
public data class IngestLag(
    val oldestPendingAge: Duration,
    val retention: Duration?,
    val lost: Map<TopicPartition, Long>,
)

/**
 * 取り込みの位置を、Kafka の今のオフセットと比べる(監視と、保持期間を超えた後の回復。ADR-0031 §7・docs/runbooks/batch-rerun.md)。
 *
 * - [lag]: 取り込み残しの最も古いイベントの時刻を読み、保持期間と一緒に返す。取り込みが止まっていても、保持期間に近づいたことが分かる
 *   (Prometheus の保持期間は短いので、Kafka のオフセットの変化からは数日前の遅れを判定できないため)。
 * - [skipLost]: 消えた範囲を飛ばした位置(パーティションごとに、保存した位置とトピックの先頭の大きい方)を返す。運用者が確かめてから使う。
 *
 * @param consumers 読むたびに作って閉じる(Consumer Group に参加しない。assign で読む)
 */
public class KafkaIngestPositions(
    private val consumers: () -> Consumer<ByteArray?, ByteArray?>,
    private val admin: Admin,
    private val pollTimeout: Duration = 10.seconds,
) {
    public suspend fun lag(
        position: SourcePosition?,
        now: Instant,
    ): Result<IngestLag, DomainError> =
        kafka {
            consumers().use { consumer ->
                val offsets = offsets(consumer)
                val stored = position?.let(KafkaOrderEventSource::decode).orEmpty()
                val lost = lost(offsets, stored)
                // 取り込み残しの先頭(消えていれば、今読める先頭)の時刻
                val oldest =
                    offsets.partitions
                        .mapNotNull { partition ->
                            val from = maxOf(stored[partition] ?: 0L, offsets.begins.getValue(partition))
                            if (from < offsets.ends.getValue(partition)) timestampAt(consumer, partition, from) else null
                        }.minOrNull()
                IngestLag(oldest?.let { (now - it).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO, retention(), lost)
            }
        }

    /** 消えた範囲を飛ばした位置と、飛ばす件数(パーティションごと)。位置を書き換えるのは呼び出し側。 */
    public suspend fun skipLost(position: SourcePosition?): Result<Pair<SourcePosition, Map<TopicPartition, Long>>, DomainError> =
        kafka {
            consumers().use { consumer ->
                val offsets = offsets(consumer)
                val stored = position?.let(KafkaOrderEventSource::decode).orEmpty()
                val next =
                    offsets.partitions.associateWith { partition ->
                        maxOf(stored[partition] ?: offsets.begins.getValue(partition), offsets.begins.getValue(partition))
                    }
                KafkaOrderEventSource.encode(next) to lost(offsets, stored)
            }
        }

    private class Offsets(
        val partitions: List<TopicPartition>,
        val begins: Map<TopicPartition, Long>,
        val ends: Map<TopicPartition, Long>,
    )

    private fun offsets(consumer: Consumer<ByteArray?, ByteArray?>): Offsets {
        val partitions =
            KafkaOrderEventSource.TOPICS.flatMap { topic -> consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) } }
        return Offsets(partitions, consumer.beginningOffsets(partitions), consumer.endOffsets(partitions))
    }

    /** 保存した位置より先頭が進んでいる(間のイベントが消えた)パーティションと、消えた件数。 */
    private fun lost(
        offsets: Offsets,
        stored: Map<TopicPartition, Long>,
    ): Map<TopicPartition, Long> =
        stored
            .mapNotNull { (partition, at) ->
                val begin = offsets.begins[partition] ?: return@mapNotNull null
                if (at < begin) partition to (begin - at) else null
            }.toMap()

    private fun timestampAt(
        consumer: Consumer<ByteArray?, ByteArray?>,
        partition: TopicPartition,
        offset: Long,
    ): Instant? {
        consumer.assign(listOf(partition))
        consumer.seek(partition, offset)
        val deadline = TimeSource.Monotonic.markNow() + pollTimeout
        while (deadline.hasNotPassedNow()) {
            val record = consumer.poll(POLL.toJavaDuration()).firstOrNull { it.offset() >= offset }
            if (record != null) return Instant.fromEpochMilliseconds(record.timestamp())
        }
        return null
    }

    private fun retention(): Duration? {
        val resources = KafkaOrderEventSource.TOPICS.map { ConfigResource(ConfigResource.Type.TOPIC, it) }
        return admin
            .describeConfigs(resources)
            .all()
            .get()
            .values
            .mapNotNull { it.get(TopicConfig.RETENTION_MS_CONFIG)?.value()?.toLongOrNull() }
            .filter { it > 0 }
            .minOrNull()
            ?.milliseconds
    }

    private suspend fun <T> kafka(block: () -> T): Result<T, DomainError> =
        withContext(Dispatchers.IO) {
            try {
                ok(block())
            } catch (e: ExecutionException) {
                err(UnavailableError("Kafka に問い合わせられません(${e.cause?.let { it::class.simpleName }})"))
            } catch (e: KafkaException) {
                err(UnavailableError("Kafka に問い合わせられません(${e::class.simpleName})"))
            }
        }

    private companion object {
        val POLL = 500.milliseconds
    }
}
