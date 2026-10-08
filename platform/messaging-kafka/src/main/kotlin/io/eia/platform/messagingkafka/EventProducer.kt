package io.eia.platform.messagingkafka

import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.CurrentTrace
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.RetriableException
import kotlin.coroutines.resume
import kotlin.time.Clock

/**
 * イベントを Kafka に直接送る(Framework 6。ADR-0025 §4)。
 *
 * **DB の更新と組み合わせるイベントは、これを使わず Outbox(ADR-0007)で発行する**(二重書き込みの禁止。CLAUDE.md §5)。
 * これを使うのは、DB の更新を伴わない送信だけ(例: CDC の Anti-Corruption 変換が、受け取ったイベントを整形して送り直す)。
 *
 * - `ce_id` は UUIDv7([EventIds])。
 * - 送信ごとに PRODUCER の span(`{topic} publish`)を作り、その `traceparent` と Correlation ID をヘッダに入れる
 *   (CloudEvents binary mode。[EventMetadata])。Correlation ID は呼び出し元のコンテキストのものを引き継ぐ(なければ採番する)。
 * - キーはパーティションキー(集約の ID など)。同じキーのイベントは同じパーティションに入り、順序が保たれる。
 * - Kafka の送信の完了(acks=all)まで待つ。失敗は [PublishFailed](Kafka の `RetriableException` なら Retryable)。
 * - ログ・span・エラーにペイロードの値は入れない。
 *
 * [producer] は [KafkaProducerSettings] で作り、サービスで 1 つを共有する(閉じるのは作った側)。
 *
 * @param source `ce_source`(例 `/sales/order-service`)
 */
public class EventProducer(
    private val producer: Producer<ByteArray, ByteArray>,
    private val observability: ObservabilityRuntime,
    private val source: String,
    private val clock: Clock = Clock.System,
    private val ids: EventIds = EventIds(clock),
) {
    init {
        require(source.isNotBlank()) { "source が空です" }
    }

    /** [value] を [serializer] のトピックに送る。 */
    public suspend fun <T> send(
        serializer: AvroEventSerializer<T>,
        key: String,
        value: T,
    ): Result<PublishedEvent, MessagingError> = publish(serializer.topic, key) { serializer.serialize(value) }

    /**
     * [topic] に [key] の tombstone(値のないレコード)を送る。compacted のトピックで、そのキーの削除を表す(ADR-0026 §9)。
     * ヘッダ(CloudEvents・traceparent・correlationid)は [send] と同じものを付ける。
     */
    public suspend fun sendTombstone(
        topic: EventTopic,
        key: String,
    ): Result<PublishedEvent, MessagingError> = publish(topic, key) { ok(null) }

    private suspend fun publish(
        topic: EventTopic,
        key: String,
        payload: () -> Result<ByteArray?, MessagingError>,
    ): Result<PublishedEvent, MessagingError> =
        observability.withSpan("${topic.name} publish", SpanKind.PRODUCER) { span ->
            span.setAttribute(MESSAGING_SYSTEM, KAFKA)
            span.setAttribute(MESSAGING_DESTINATION, topic.name)
            span.setAttribute(MESSAGING_OPERATION, OPERATION_SEND)
            when (val serialized = payload()) {
                is Result.Err -> {
                    span.setAttribute(ERROR_TYPE, serialized.error.code)
                    serialized
                }

                is Result.Ok -> {
                    val trace = CurrentTrace.get()
                    val metadata =
                        EventMetadata(
                            id = ids.next(),
                            source = source,
                            type = topic.ceType,
                            time = clock.now(),
                            traceParent = requireNotNull(trace.traceParent) { "PRODUCER の span の traceparent がありません" },
                            correlationId = requireNotNull(trace.correlationId) { "Correlation ID がありません" },
                        )
                    span.setAttribute(MESSAGING_MESSAGE_ID, metadata.id.toString())
                    send(topic, key, serialized.value, metadata).also { result ->
                        (result as? Result.Err)?.let { span.setAttribute(ERROR_TYPE, it.error.code) }
                    }
                }
            }
        }

    private suspend fun send(
        topic: EventTopic,
        key: String,
        payload: ByteArray?,
        metadata: EventMetadata,
    ): Result<PublishedEvent, MessagingError> {
        val record = ProducerRecord(topic.name, null, key.toByteArray(Charsets.UTF_8), payload, metadata.toHeaders())
        return try {
            suspendCancellableCoroutine { continuation ->
                producer.send(record) { recordMetadata, exception ->
                    val result =
                        if (exception == null) {
                            ok(PublishedEvent(metadata, recordMetadata.partition(), recordMetadata.offset()))
                        } else {
                            err(publishFailed(topic, exception))
                        }
                    continuation.resume(result)
                }
            }
        } catch (e: KafkaException) {
            // send() 自体が投げる例外(メタデータの取得の待ちの超過・設定の誤りなど)
            err(publishFailed(topic, e))
        }
    }

    private fun publishFailed(
        topic: EventTopic,
        exception: Exception,
    ): PublishFailed {
        val reason = exception::class.simpleName ?: "unknown"
        return if (exception is RetriableException) {
            PublishFailed.Retryable(
                topic.name,
                reason,
            )
        } else {
            PublishFailed.NonRetryable(topic.name, reason)
        }
    }

    private companion object {
        // OTel のメッセージングの意味規約(semconv の incubating にあるため、キーを直接書く)
        val MESSAGING_SYSTEM: AttributeKey<String> = AttributeKey.stringKey("messaging.system")
        val MESSAGING_DESTINATION: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.name")
        val MESSAGING_OPERATION: AttributeKey<String> = AttributeKey.stringKey("messaging.operation.type")
        val MESSAGING_MESSAGE_ID: AttributeKey<String> = AttributeKey.stringKey("messaging.message.id")
        val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        const val KAFKA = "kafka"
        const val OPERATION_SEND = "send"
    }
}

/** 送信したイベント。 */
public data class PublishedEvent(
    public val metadata: EventMetadata,
    public val partition: Int,
    public val offset: Long,
)
