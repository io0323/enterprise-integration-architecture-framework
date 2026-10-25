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
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * イベントを Kafka に直接送る(Framework 6。ADR-0025 §4)。
 *
 * **DB の更新と組み合わせるイベントは、これを使わず Outbox(ADR-0007)で発行する**(二重書き込みの禁止。CLAUDE.md §5)。
 * これを使うのは、DB の更新を伴わない送信だけ(例: CDC の Anti-Corruption 変換が、受け取ったイベントを整形して送り直す)。
 *
 * - `ce_id` は UUIDv7([EventIds])、`ce_time` は送る時刻。送り手が識別子と時刻を決める場合([EventIdentity]。例 iot-bridge は
 *   デバイスのメッセージから作る UUID の版 5 と受け取った時刻。ADR-0039 §5・§7)は、それを使い、Kafka のレコードの時刻もその時刻にする。
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

    /**
     * [value] を [serializer] のトピックに送る。
     *
     * @param identity `ce_id` と `ce_time`(と Kafka のレコードの時刻)を送り手が決めるときに渡す。なければ UUIDv7 と送る時刻
     */
    public suspend fun <T> send(
        serializer: AvroEventSerializer<T>,
        key: String,
        value: T,
        identity: EventIdentity? = null,
    ): Result<PublishedEvent, MessagingError> = publish(serializer.topic, key, identity) { serializer.serialize(value) }

    /**
     * [topic] に [key] の tombstone(値のないレコード)を送る。compacted のトピックで、そのキーの削除を表す(ADR-0026 §9)。
     * ヘッダ(CloudEvents・traceparent・correlationid)は [send] と同じものを付ける。
     */
    public suspend fun sendTombstone(
        topic: EventTopic,
        key: String,
    ): Result<PublishedEvent, MessagingError> = publish(topic, key, identity = null) { ok(null) }

    private suspend fun publish(
        topic: EventTopic,
        key: String,
        identity: EventIdentity?,
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
                            id = identity?.id ?: ids.next(),
                            source = source,
                            type = topic.ceType,
                            time = identity?.time ?: clock.now(),
                            traceParent = requireNotNull(trace.traceParent) { "PRODUCER の span の traceparent がありません" },
                            correlationId = requireNotNull(trace.correlationId) { "Correlation ID がありません" },
                        )
                    span.setAttribute(MESSAGING_MESSAGE_ID, metadata.id.toString())
                    send(topic, key, serialized.value, metadata, timestamp = identity?.time).also { result ->
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
        timestamp: Instant?,
    ): Result<PublishedEvent, MessagingError> {
        // レコードの時刻(保持期間の起点)。指定がなければ Kafka の既定(送る時刻)
        val record =
            ProducerRecord(
                topic.name,
                null,
                timestamp?.toEpochMilliseconds(),
                key.toByteArray(Charsets.UTF_8),
                payload,
                metadata.toHeaders(),
            )
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

/**
 * 送り手が決める `ce_id` と `ce_time`。同じメッセージを送り直しても同じ `ce_id` になるように、送り手が元のメッセージから作る
 * (受信側の冪等の判定に使う)。[time] は Kafka のレコードの時刻にもする(保持期間の起点)。
 */
public data class EventIdentity(
    public val id: Uuid,
    public val time: Instant,
)

/** 送信したイベント。 */
public data class PublishedEvent(
    public val metadata: EventMetadata,
    public val partition: Int,
    public val offset: Long,
)
