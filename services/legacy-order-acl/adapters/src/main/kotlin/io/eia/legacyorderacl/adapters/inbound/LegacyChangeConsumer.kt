package io.eia.legacyorderacl.adapters.inbound

import io.eia.shared.kernel.RetryPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * 生の CDC のトピックを読み、1 件ずつ順に処理して、処理を終えたオフセットだけをコミットする(At-Least-Once。ADR-0026 §5・§6)。
 *
 * - 1 つの Consumer(1 つのスレッド)で、パーティションごとに届いた順に処理する(並列にしない)。同じ注文の変更の順序を保つ。
 * - 1 件の処理([LegacyChangeProcessor])は発行(または DLQ)の完了まで待つので、[committer] が呼ばれる時点で、
 *   コミットするオフセットより前の変更はすべて発行が確定している。コミットの前に落ちれば、最後のコミットの位置から送り直す(重複のみ)。
 * - 一時的な失敗では、処理を終えた分までをコミットし、残りのパーティションを未処理の位置に戻して、Backoff の後に読み直す。
 *   その間 [ready] は false(`/health/ready` が 503)。
 *
 * Kafka の Consumer はスレッドセーフでないので、[run] は 1 つのスレッドの Dispatcher で動かす(app が用意する)。
 */
public class LegacyChangeConsumer(
    private val consumer: Consumer<ByteArray?, ByteArray?>,
    private val processor: LegacyChangeProcessor,
    private val retry: RetryPolicy = RetryPolicy(maxAttempts = Int.MAX_VALUE),
    private val committer: OffsetCommitter = OffsetCommitter { c, offsets -> c.commitSync(offsets) },
    private val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
    private val random: Random = Random.Default,
) {
    @Volatile
    private var healthy = false

    @Volatile
    private var assigned = false

    /** 直近の処理が一時的な失敗でなく、パーティションを割り当てられている。 */
    public val ready: Boolean get() = healthy && assigned

    /** [topic] を購読し、キャンセルされるまで処理を続ける。終わるときに Consumer を閉じる(同じスレッドで)。 */
    public suspend fun run(topic: String = TOPIC) {
        try {
            consume(topic)
        } finally {
            healthy = false
            consumer.close()
        }
    }

    private suspend fun consume(topic: String) {
        consumer.subscribe(listOf(topic))
        healthy = true
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val failure = pollOnce()
            if (failure == null) {
                failures = 0
                healthy = true
            } else {
                failures++
                healthy = false
                val wait = retry.backoff(failures, random)
                logger.warn("一時的な失敗のため、{} の後に最後のコミットの位置から読み直します({}。{} 回目)", wait, failure, failures)
                delay(wait)
            }
        }
    }

    /** 1 回 poll して処理する。一時的な失敗のときはそのエラーのコード、それ以外は null。 */
    internal suspend fun pollOnce(): String? {
        val records = consumer.poll(pollTimeout.toJavaDuration())
        assigned = consumer.assignment().isNotEmpty()
        val processed = mutableMapOf<TopicPartition, OffsetAndMetadata>()
        // パーティションごとに、届いた順に処理する
        val ordered = records.partitions().flatMap { records.records(it) }
        for (record in ordered) {
            val partition = TopicPartition(record.topic(), record.partition())
            val outcome = processor.process(record)
            if (outcome is LegacyChangeProcessor.RetryLater) {
                commit(processed)
                // このバッチの各パーティションを、未処理の最初の位置に戻す(処理済みの分は送り直さない)
                records.partitions().forEach { p -> consumer.seek(p, processed[p]?.offset() ?: records.records(p).first().offset()) }
                processor.retried(outcome.error.code)
                return outcome.error.code
            }
            processed[partition] = OffsetAndMetadata(record.offset() + 1)
        }
        commit(processed)
        return null
    }

    private fun commit(offsets: Map<TopicPartition, OffsetAndMetadata>) {
        if (offsets.isNotEmpty()) committer.commit(consumer, offsets)
    }

    public companion object {
        public const val TOPIC: String = "_cdc.legacy.public.t_juchu"
        public const val GROUP_ID: String = "legacy-order-acl.translate"
        private val DEFAULT_POLL_TIMEOUT = 500.milliseconds
        private const val MAX_POLL_RECORDS = 100
        private val logger = LoggerFactory.getLogger(LegacyChangeConsumer::class.java)

        /**
         * Consumer の設定。オフセットは処理の後に明示的にコミットする(`enable.auto.commit=false`)。初めて読むときはトピックの最初から読む。
         */
        public fun consumerProperties(
            bootstrapServers: String,
            groupId: String = GROUP_ID,
        ): Map<String, Any> =
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to groupId,
                ConsumerConfig.CLIENT_ID_CONFIG to "legacy-order-acl",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to MAX_POLL_RECORDS,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            )
    }
}

/** 処理を終えたオフセットのコミット。統合テストで、発行の後・コミットの前に止める場合の差し替えに使う。 */
public fun interface OffsetCommitter {
    public fun commit(
        consumer: Consumer<ByteArray?, ByteArray?>,
        offsets: Map<TopicPartition, OffsetAndMetadata>,
    )
}
