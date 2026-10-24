package io.eia.shared.sdk.mqtt

import io.eia.shared.kernel.DomainError

/**
 * MQTT の失敗(ADR-0038)。各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する(ADR-0011)。
 * メッセージには理由と MQTT v5 の理由のコードだけを入れる(資格情報・ペイロードは入れない)。
 */
public sealed interface MqttError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is MqttUnavailable -> this
            is MqttConnectRejected -> this
            is MqttPublishRejected -> this
            is MqttInvalidMessage -> this
        }
}

/**
 * Broker に一時的につながらない・送れない(接続の失敗・タイムアウト・接続が切れた・Broker が混んでいる)。再接続して送り直してよい。
 *
 * @param reason `connect_failed` / `connect_timeout` / `connection_lost` / `ack_timeout` / `keepalive_timeout` / `protocol_error` /
 *   `server_busy`(CONNACK・PUBACK・DISCONNECT の一時的な理由のコード)
 * @param reasonCode MQTT v5 の理由のコード(Broker が返したとき)
 */
public data class MqttUnavailable(
    public val reason: String,
    public val reasonCode: Int? = null,
) : MqttError,
    DomainError.Retryable {
    override val code: String get() = "mqtt_unavailable"
    override val message: String get() = "MQTT Broker に一時的に送れません($reason${reasonCode?.let { ", reasonCode=0x${it.hex()}" }.orEmpty()})"
}

/** CONNACK で接続を拒否された(資格情報の誤り・許可がない・使用禁止など)。送り直しても解決しない。 */
public data class MqttConnectRejected(
    public val reasonCode: Int,
) : MqttError,
    DomainError.NonRetryable {
    override val code: String get() = "mqtt_connect_rejected"
    override val message: String get() = "MQTT Broker が接続を拒否しました(reasonCode=0x${reasonCode.hex()})"
}

/**
 * PUBACK で PUBLISH を拒否された(ACL で書けないトピック(0x87 Not authorized)・トピック名の誤り・大きすぎるなど)。
 * 送り直しても解決しない。[reasonString] は Broker の説明(ペイロードは含まない)。
 */
public data class MqttPublishRejected(
    public val reasonCode: Int,
    public val reasonString: String? = null,
) : MqttError,
    DomainError.NonRetryable {
    override val code: String get() = "mqtt_publish_rejected"
    override val message: String get() = "MQTT Broker が PUBLISH を拒否しました(reasonCode=0x${reasonCode.hex()})"
}

/** 送る前の検査の失敗(トピックの形・Broker の上限を超える大きさ・Broker が受け付けない QoS)。 */
public data class MqttInvalidMessage(
    public val reason: String,
) : MqttError,
    DomainError.NonRetryable {
    override val code: String get() = "mqtt_invalid_message"
    override val message: String get() = "MQTT のメッセージが不正です($reason)"
}

private const val HEX_RADIX = 16

internal fun Int.hex(): String = toString(HEX_RADIX).padStart(2, '0')
