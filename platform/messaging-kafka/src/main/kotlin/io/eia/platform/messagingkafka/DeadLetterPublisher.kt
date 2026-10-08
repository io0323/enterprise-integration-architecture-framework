package io.eia.platform.messagingkafka

import io.eia.platform.observability.context.CurrentTrace
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import kotlin.coroutines.resume
import kotlin.time.Clock

/**
 * 処理できないメッセージを DLQ(`{topic}.dlq`)に隔離する(Framework 6.5。INTEGRATION_STANDARDS §1・§2。ADR-0026 §7)。
 *
 * - キーと値は **受け取ったバイト列のまま** 送る(原因を除いた後に、同じ内容で調べ直せるように)。
 * - ヘッダは元のヘッダに [DeadLetterHeaders] を加える。`traceparent` は今の span のもの(処理したトレース)に置き換える。
 * - 原因の詳細([DeadLetterReason.detail])には値を入れない(呼び出し元の責務。ペイロードの全文のログの禁止)。
 * - Kafka の送信の完了(acks=all)まで待つ。DLQ に送れなければ、元のメッセージのオフセットを進めてはならない(呼び出し元の責務)。
 *
 * [producer] は [KafkaProducerSettings] で作る(サービスで共有してよい)。
 */
public class DeadLetterPublisher(
    private val producer: Producer<ByteArray, ByteArray>,
    private val clock: Clock = Clock.System,
) {
    public suspend fun send(
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        reason: DeadLetterReason,
        attempts: Int = 1,
    ): Result<DeadLettered, MessagingError> {
        require(attempts >= 1) { "attempts は 1 以上にしてください" }
        val topic = deadLetterTopic(record.topic())
        val trace = CurrentTrace.get().traceParent
        val headers =
            record.headers().filterNot { it.key() == TraceParent.HEADER || it.key().startsWith(DeadLetterHeaders.PREFIX) } +
                listOfNotNull(trace?.let { header(TraceParent.HEADER, it.format()) }) +
                listOf(
                    header(DeadLetterHeaders.REASON, reason.code),
                    header(DeadLetterHeaders.DETAIL, reason.detail),
                    header(DeadLetterHeaders.SOURCE_TOPIC, record.topic()),
                    header(DeadLetterHeaders.SOURCE_PARTITION, record.partition().toString()),
                    header(DeadLetterHeaders.SOURCE_OFFSET, record.offset().toString()),
                    header(DeadLetterHeaders.ATTEMPTS, attempts.toString()),
                    header(DeadLetterHeaders.FAILED_AT, clock.now().toString()),
                )
        // パーティションは指定しない(キーで決まる。同じキーの DLQ のレコードは同じパーティションに順に入る)
        val dead = ProducerRecord<ByteArray, ByteArray>(topic, null, record.key(), record.value(), headers)
        return try {
            suspendCancellableCoroutine { continuation ->
                producer.send(dead) { metadata, exception ->
                    continuation.resume(
                        if (exception == null) {
                            ok(DeadLettered(topic, metadata.partition(), metadata.offset()))
                        } else {
                            err(failed(topic, exception))
                        },
                    )
                }
            }
        } catch (e: KafkaException) {
            err(failed(topic, e))
        }
    }

    private fun failed(
        topic: String,
        exception: Exception,
    ): PublishFailed {
        val reason = exception::class.simpleName ?: "unknown"
        return if (exception is RetriableException) PublishFailed.Retryable(topic, reason) else PublishFailed.NonRetryable(topic, reason)
    }

    private fun header(
        key: String,
        value: String,
    ): Header = RecordHeader(key, value.toByteArray(Charsets.UTF_8))

    public companion object {
        public const val SUFFIX: String = ".dlq"

        /** [topic] の DLQ のトピック名(`{topic}.dlq`)。 */
        public fun deadLetterTopic(topic: String): String = topic + SUFFIX
    }
}

/**
 * DLQ に送る理由。
 *
 * @property code 原因の種類(例 `UNKNOWN_STATUS_CODE`)。メトリクスのラベルにも使うため、種類の数は有限にする
 * @property detail 列・項目の名前と破った規則(**値は入れない**)
 */
public data class DeadLetterReason(
    public val code: String,
    public val detail: String,
) {
    init {
        require(code.isNotBlank()) { "code が空です" }
    }
}

/** DLQ に送ったレコードの位置。 */
public data class DeadLettered(
    public val topic: String,
    public val partition: Int,
    public val offset: Long,
)

/** DLQ のレコードに付けるヘッダ(INTEGRATION_STANDARDS §2。ADR-0026 §7。P07 の DLQ も同じ名前を使う)。値は UTF-8 の文字列。 */
public object DeadLetterHeaders {
    public const val PREFIX: String = "eiaf.dlq."
    public const val REASON: String = "${PREFIX}reason"
    public const val DETAIL: String = "${PREFIX}detail"
    public const val SOURCE_TOPIC: String = "${PREFIX}source.topic"
    public const val SOURCE_PARTITION: String = "${PREFIX}source.partition"
    public const val SOURCE_OFFSET: String = "${PREFIX}source.offset"
    public const val ATTEMPTS: String = "${PREFIX}attempts"
    public const val FAILED_AT: String = "${PREFIX}failed-at"
}
