package io.eia.platform.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventIds
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.MessagingError
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.CurrentTrace
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import kotlin.time.Clock

/**
 * 業務のイベントから [OutboxRecord] を作る(ADR-0007・ADR-0025)。
 *
 * - ペイロードは [AvroEventSerializer] で作る(契約のスキーマ。起動時に解決したスキーマ ID。レジストリには問い合わせない)。
 * - `ce_id` は UUIDv7([EventIds])。Outbox の `id` 列にも同じ値を入れる。
 * - 作るたびに PRODUCER の span(`{topic} create`)を作り、その `traceparent` と呼び出し元の Correlation ID(なければ採番)を記録に入れる。
 *   リクエストの外(バッチなど)で呼ばれても traceparent が必ず入り、受信側(P07)の span はこの span の子になる。
 *   span はトランザクションの確定を待たずに閉じる(発行は Debezium が確定の後に行う)。
 *
 * @param source `ce_source`(例 `/sales/order-service`)
 */
public class OutboxEvents(
    private val observability: ObservabilityRuntime,
    private val source: String,
    private val clock: Clock = Clock.System,
    private val ids: EventIds = EventIds(clock),
) {
    init {
        require(source.isNotBlank()) { "source が空です" }
    }

    public suspend fun <T> create(
        serializer: AvroEventSerializer<T>,
        aggregateType: String,
        aggregateId: String,
        value: T,
    ): Result<OutboxRecord, MessagingError> {
        val topic = serializer.topic
        return observability.withSpan("${topic.name} create", SpanKind.PRODUCER) { span ->
            span.setAttribute(MESSAGING_SYSTEM, KAFKA)
            span.setAttribute(MESSAGING_DESTINATION, topic.name)
            span.setAttribute(MESSAGING_OPERATION, OPERATION_CREATE)
            serializer
                .serialize(value)
                .map { payload ->
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
                    OutboxRecord(topic, aggregateType, aggregateId, metadata, payload)
                }.also { result -> (result as? Result.Err)?.let { span.setAttribute(ERROR_TYPE, it.error.code) } }
        }
    }

    private companion object {
        // OTel のメッセージングの意味規約(EventProducer と同じキー)。Outbox への追記は operation.type=create
        val MESSAGING_SYSTEM: AttributeKey<String> = AttributeKey.stringKey("messaging.system")
        val MESSAGING_DESTINATION: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.name")
        val MESSAGING_OPERATION: AttributeKey<String> = AttributeKey.stringKey("messaging.operation.type")
        val MESSAGING_MESSAGE_ID: AttributeKey<String> = AttributeKey.stringKey("messaging.message.id")
        val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        const val KAFKA = "kafka"
        const val OPERATION_CREATE = "create"
    }
}
