package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.adapters.outbound.IotEventSchemas
import io.eia.iotbridge.application.port.inbound.InboundTelemetry
import io.eia.iotbridge.application.port.inbound.IngestTelemetryUseCase
import io.eia.iotbridge.application.port.inbound.MessageContext
import io.eia.iotbridge.domain.DeviceAddress
import io.eia.iotbridge.domain.TelemetryRejection
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterReason
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
 *   捨てずに `iot.telemetry.reported.v1.dlq` に、受け取ったバイト列のまま隔離する(§4)。元のトピックは `eiaf.mqtt.topic`。
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

    public suspend fun handle(message: ReceivedMqttMessage): HandlingResult {
        val properties = message.userProperties.associate { it }
        val parent = propagator.extract(Context.root(), properties, UserPropertiesGetter)
        val correlationId = properties[CORRELATION_ID]?.let { CorrelationId.parse(it).getOrNull() } ?: CorrelationId.generate()
        return withContext(ObservabilityContext(correlationId, INTEGRATION_ID, parent)) {
            observability.withSpan(SPAN_NAME, SpanKind.CONSUMER) { span ->
                span.setAttribute(MESSAGING_SYSTEM, MQTT)
                span.setAttribute(MESSAGING_DESTINATION_TEMPLATE, SUBSCRIPTION)
                process(span, message, MessageContext(properties[TRACEPARENT], correlationId.value))
            }
        }
    }

    private suspend fun process(
        span: Span,
        message: ReceivedMqttMessage,
        context: MessageContext,
    ): HandlingResult =
        when (val decoded = decoder.decode(message.payload)) {
            is Result.Err -> {
                deadLetter(span, message, decoded.error.reason.name, decoded.error.detail)
            }

            is Result.Ok -> {
                when (val result = ingest(InboundTelemetry(message.topic, decoded.value, message.receivedAt, context))) {
                    is Result.Ok -> {
                        metrics.published(result.value)
                        HandlingResult.ACKNOWLEDGE
                    }

                    is Result.Err -> {
                        failed(span, message, result.error)
                    }
                }
            }
        }

    private suspend fun failed(
        span: Span,
        message: ReceivedMqttMessage,
        error: DomainError,
    ): HandlingResult =
        when (error) {
            is TelemetryRejection -> {
                deadLetter(span, message, error.reason.name, error.detail)
            }

            is DomainError.Retryable -> {
                span.setAttribute(ERROR_TYPE, error.code)
                logger.warn("テレメトリを発行できません。PUBACK を返さずに送り直します code={}", error.code)
                metrics.retried()
                HandlingResult.RETRY
            }

            is DomainError.NonRetryable -> {
                deadLetter(span, message, UNPUBLISHABLE, error.code)
            }
        }

    private suspend fun deadLetter(
        span: Span,
        message: ReceivedMqttMessage,
        reason: String,
        detail: String,
    ): HandlingResult {
        span.setAttribute(ERROR_TYPE, reason)
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
                    reason = DeadLetterReason(reason, detail),
                    origin = mapOf(MQTT_TOPIC_HEADER to message.topic),
                )
        ) {
            is Result.Ok -> {
                logger.warn("テレメトリを DLQ に隔離しました reason={} detail={}", reason, detail)
                metrics.rejected(reason)
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
