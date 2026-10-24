package io.eia.shared.sdk.mqtt

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.resilience.trace.TraceParent
import kotlin.random.Random

/** MQTT v5 のユーザープロパティ(名前と値の組。同じ名前を複数持てる)。 */
public data class UserProperty(
    public val name: String,
    public val value: String,
)

/** QoS(ADR-0038。最小限の実装は 0 と 1 だけ。テレメトリは 1。ADR-0039)。 */
public enum class Qos(
    internal val value: Int,
) {
    AT_MOST_ONCE(0),
    AT_LEAST_ONCE(1),
}

/**
 * 送るメッセージ。retain は使わない(ADR-0039 §9)。
 *
 * @param contentType MQTT v5 の Content Type(例 `application/json`)
 */
public class MqttMessage(
    public val topic: String,
    public val payload: ByteArray,
    public val qos: Qos = Qos.AT_LEAST_ONCE,
    public val userProperties: List<UserProperty> = emptyList(),
    public val contentType: String? = null,
) {
    init {
        require(topic.isNotEmpty() && '+' !in topic && '#' !in topic) { "トピックにワイルドカードは使えません" }
    }

    /**
     * `traceparent` と `correlationid` をユーザープロパティに付けた写し(INTEGRATION_STANDARDS §2。ADR-0039 §10)。
     * traceparent は [parent] の子(なければ新しいトレースを始める)、Correlation ID は [correlationId](なければ採番)。
     * 既に付いていれば置き換える。
     */
    public fun withTrace(
        parent: TraceParent? = null,
        correlationId: CorrelationId? = null,
        random: Random = Random.Default,
    ): MqttMessage {
        val traceParent = parent?.child(random) ?: TraceParent.generate(random)
        val correlation = correlationId ?: CorrelationId.generate(random)
        val others = userProperties.filterNot { it.name == TRACEPARENT || it.name == CORRELATION_ID }
        return MqttMessage(
            topic = topic,
            payload = payload,
            qos = qos,
            userProperties = others + UserProperty(TRACEPARENT, traceParent.format()) + UserProperty(CORRELATION_ID, correlation.value),
            contentType = contentType,
        )
    }

    public companion object {
        /** INTEGRATION_STANDARDS §2 の MQTT v5 のユーザープロパティの名前。 */
        public const val TRACEPARENT: String = "traceparent"
        public const val CORRELATION_ID: String = "correlationid"
    }
}

/**
 * PUBLISH の結果。QoS 1 では PUBACK の理由のコード(0x00 Success か 0x10 No matching subscribers)。QoS 0 では null。
 */
public data class PublishResult(
    public val reasonCode: Int?,
)
