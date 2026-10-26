package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.adapters.outbound.IotEventSchemas
import io.eia.iotbridge.application.port.inbound.InboundTelemetry
import io.eia.iotbridge.application.port.inbound.IngestTelemetryUseCase
import io.eia.iotbridge.application.port.inbound.MessageContext
import io.eia.iotbridge.domain.DeviceAddress
import io.eia.iotbridge.domain.TelemetryRejection
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterReason
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.ReplayOutcome
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Instant

/** MQTT から受け取った 1 件のメッセージ(購読のライブラリによらない形)。 */
public class ReceivedMqttMessage(
    public val topic: String,
    public val payload: ByteArray,
    public val userProperties: List<Pair<String, String>>,
    public val receivedAt: Instant,
)

/** 1 件を処理した結果。 */
public enum class HandlingResult {
    /** 発行した、または DLQ に隔離した。MQTT の PUBACK を返してよい。 */
    ACKNOWLEDGE,

    /** 一時的に処理できない(Kafka に送れないなど)。PUBACK を返さずに、待ってから同じメッセージを処理し直す。 */
    RETRY,
}

/**
 * MQTT のテレメトリの 1 件を処理する(ADR-0039)。
 *
 * - **トレース**: MQTT v5 のユーザープロパティの `traceparent` の子の CONSUMER の span で処理する。Correlation ID は `correlationid` を
 *   引き継ぐ。どちらもなければ(MQTT v3.1.1 のデバイス)新しく始める(§10)。span の名前にはデバイスの ID を入れない。
 * - **読み取り**: 形式のスキーマで検証して読む([DevicePayloadDecoder])。受け付けないもの(知らない版・形式の誤り・トピックの誤り)は
 *   捨てずに `iot.telemetry.reported.v1.dlq` に、受け取ったバイト列のまま隔離する(§4)。元のトピック・受け取った時刻・Correlation ID は
 *   ヘッダ(`eiaf.mqtt.topic`・`eiaf.mqtt.received-at`・`correlationid`)。再処理([reprocess])でそのまま引き継ぐ。
 * - **発行**: 一時的な失敗([DomainError.Retryable])は [HandlingResult.RETRY](PUBACK を返さない)。DLQ に送れないときも RETRY。
 *   恒久的な発行の失敗(契約と実装の食い違いなど)は DLQ に隔離する(`UNPUBLISHABLE`)。
 * - ログ・span・DLQ の説明にペイロードの値は入れない。
 */
public class TelemetryMessageHandler(
    private val ingest: IngestTelemetryUseCase,
    private val decoder: DevicePayloadDecoder,
    private val deadLetters: DeadLetterPublisher,
    private val observability: ObservabilityRuntime,
    private val metrics: TelemetryMetrics,
) {
    private val propagator = observability.openTelemetry.propagators.textMapPropagator

    @Volatile
    private var lastRetried = false

    /** 直近の処理が一時的な失敗(Kafka に送れない)で、送り直しを待っているか(準備のチェック)。 */
    public val isRetrying: Boolean get() = lastRetried

    /** MQTT から受け取ったものを処理する。受け付けないものは DLQ に隔離する。 */
    public suspend fun handle(message: ReceivedMqttMessage): HandlingResult =
        traced(message) { span, context ->
            when (val processed = process(span, message, context)) {
                Processed.Published -> HandlingResult.ACKNOWLEDGE
                Processed.Retry -> HandlingResult.RETRY
                is Processed.Rejected -> deadLetter(span, message, context, processed)
            }
        }.also { lastRetried = it == HandlingResult.RETRY }

    /**
     * DLQ から取り出したものを、デコードからやり直して発行する(iot-bridge の `reprocess-dlq`。§4)。
     * 受け付けないものは DLQ に入れ直さない(DLQ のレコードは残る)。
     */
    internal suspend fun reprocess(message: ReceivedMqttMessage): ReplayOutcome =
        traced(message) { span, context ->
            when (val processed = process(span, message, context)) {
                Processed.Published -> {
                    ReplayOutcome.REPLAYED
                }

                Processed.Retry -> {
                    ReplayOutcome.FAILED
                }

                is Processed.Rejected -> {
                    logger.warn("再処理しても受け付けません reason={} detail={}", processed.reason, processed.detail)
                    ReplayOutcome.REJECTED
                }
            }
        }

    private suspend fun <T> traced(
        message: ReceivedMqttMessage,
        block: suspend (Span, MessageContext) -> T,
    ): T {
        val properties = message.userProperties.associate { it }
        val parent = propagator.extract(Context.root(), properties, UserPropertiesGetter)
        val correlationId = properties[CORRELATION_ID]?.let { CorrelationId.parse(it).getOrNull() } ?: CorrelationId.generate()
        return withContext(ObservabilityContext(correlationId, INTEGRATION_ID, parent)) {
            observability.withSpan(SPAN_NAME, SpanKind.CONSUMER) { span ->
                span.setAttribute(MESSAGING_SYSTEM, MQTT)
                span.setAttribute(MESSAGING_DESTINATION_TEMPLATE, SUBSCRIPTION)
                block(span, MessageContext(properties[TRACEPARENT], correlationId.value))
            }
        }
    }

    private sealed interface Processed {
        data object Published : Processed

        data object Retry : Processed

        data class Rejected(
            val reason: String,
            val detail: String,
        ) : Processed
    }

    private suspend fun process(
        span: Span,
        message: ReceivedMqttMessage,
        context: MessageContext,
    ): Processed =
        when (val decoded = decoder.decode(message.payload)) {
            is Result.Err -> {
                Processed.Rejected(decoded.error.reason.name, decoded.error.detail)
            }

            is Result.Ok -> {
                when (val result = ingest(InboundTelemetry(message.topic, decoded.value, message.receivedAt, context))) {
                    is Result.Ok -> {
                        metrics.published(result.value)
                        Processed.Published
                    }

                    is Result.Err -> {
                        failed(span, result.error)
                    }
                }
            }
        }

    private fun failed(
        span: Span,
        error: DomainError,
    ): Processed =
        when (error) {
            is TelemetryRejection -> {
                Processed.Rejected(error.reason.name, error.detail)
            }

            is DomainError.Retryable -> {
                span.setAttribute(ERROR_TYPE, error.code)
                logger.warn("テレメトリを発行できません。PUBACK を返さずに送り直します code={}", error.code)
                metrics.retried()
                Processed.Retry
            }

            is DomainError.NonRetryable -> {
                Processed.Rejected(UNPUBLISHABLE, error.code)
            }
        }

    private suspend fun deadLetter(
        span: Span,
        message: ReceivedMqttMessage,
        context: MessageContext,
        rejected: Processed.Rejected,
    ): HandlingResult {
        span.setAttribute(ERROR_TYPE, rejected.reason)
        val key =
            DeviceAddress
                .fromTopic(message.topic)
                .getOrNull()
                ?.key
                ?.toByteArray(Charsets.UTF_8)
        return when (
            val sent =
                deadLetters.sendExternal(
                    topic = IotEventSchemas.TELEMETRY_REPORTED.name,
                    key = key,
                    value = message.payload,
                    reason = DeadLetterReason(rejected.reason, rejected.detail),
                    origin =
                        mapOf(MQTT_TOPIC_HEADER to message.topic, MQTT_RECEIVED_AT_HEADER to message.receivedAt.toString()) +
                            listOfNotNull(context.correlationId?.let { EventMetadata.CORRELATION_ID to it }),
                )
        ) {
            is Result.Ok -> {
                logger.warn("テレメトリを DLQ に隔離しました reason={} detail={}", rejected.reason, rejected.detail)
                metrics.rejected(rejected.reason)
                HandlingResult.ACKNOWLEDGE
            }

            is Result.Err -> {
                logger.warn("DLQ に送れません。PUBACK を返さずに送り直します code={}", sent.error.code)
                metrics.retried()
                HandlingResult.RETRY
            }
        }
    }

    public companion object {
        /** 購読するトピックのフィルタ(ADR-0039 §3。ブリッジのユーザーは読み取りだけ)。 */
        public const val SUBSCRIPTION: String = "devices/+/+/telemetry"

        /** DLQ のヘッダ: 元の MQTT のトピック(ADR-0039 §4)。 */
        public const val MQTT_TOPIC_HEADER: String = "eiaf.mqtt.topic"

        /** DLQ のヘッダ: MQTT から受け取った時刻(ISO 8601。再処理で `receivedAt` に使う。§4・§7)。 */
        public const val MQTT_RECEIVED_AT_HEADER: String = "eiaf.mqtt.received-at"

        /** 恒久的に発行できない(契約と実装の食い違いなど)。 */
        public const val UNPUBLISHABLE: String = "UNPUBLISHABLE"

        /** 連携 ID(ログの integration_id)。 */
        public const val INTEGRATION_ID: String = "INT-IOT-001"

        private const val TRACEPARENT = "traceparent"
        private const val CORRELATION_ID = "correlationid"
        private const val SPAN_NAME = "$SUBSCRIPTION process"
        private const val MQTT = "mqtt"
        private val MESSAGING_SYSTEM: AttributeKey<String> = AttributeKey.stringKey("messaging.system")
        private val MESSAGING_DESTINATION_TEMPLATE: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.template")
        private val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        private val logger = LoggerFactory.getLogger(TelemetryMessageHandler::class.java)
    }
}

/** MQTT v5 のユーザープロパティから `traceparent`・`tracestate` を読む。 */
private object UserPropertiesGetter : TextMapGetter<Map<String, String>> {
    override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

    override fun get(
        carrier: Map<String, String>?,
        key: String,
    ): String? = carrier?.get(key)
}
