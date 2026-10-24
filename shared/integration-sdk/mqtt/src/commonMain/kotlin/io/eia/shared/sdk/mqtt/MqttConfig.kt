package io.eia.shared.sdk.mqtt

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** MQTT のパスワード。[toString] は値を伏せる(ログ・例外に入れても値は出ない)。data class にしない。 */
public class MqttPassword(
    private val value: String,
) {
    /** 値。CONNECT を作る直前にだけ使う。 */
    public fun reveal(): String = value

    override fun toString(): String = "***"
}

/**
 * Broker への接続の設定(ADR-0038)。デバイスはデバイスごとの資格情報(ユーザー名は `{tenant}/{deviceId}`)で接続する(ADR-0039 §3)。
 * 資格情報はハードコードせず、呼び出し側が環境変数などから渡す。
 *
 * @param cleanStart セッションを引き継がない(既定)。デバイスは購読しないので、Broker にセッションを残す必要がない
 * @param sessionExpiry 切断した後にセッションを残す時間(0 は残さない)
 * @param keepAlive 送るものがない間の生存確認(PINGREQ)の間隔。0 は確認しない。Broker が Server Keep Alive を返したらそれに従う
 * @param connectTimeout TCP の接続から CONNACK の受信までの上限
 * @param ackTimeout QoS 1 の PUBLISH から PUBACK の受信までの上限。超えたら接続が切れたものとして扱う
 * @param maxInFlight PUBACK を待つ PUBLISH の数の上限(Broker の Receive Maximum の方が小さければそちら)
 */
@Suppress("LongParameterList") // 設定の項目(既定値つき。名前付き引数で指定する)
public class MqttConfig(
    public val host: String,
    public val port: Int,
    public val clientId: String,
    public val username: String? = null,
    public val password: MqttPassword? = null,
    public val cleanStart: Boolean = true,
    public val sessionExpiry: Duration = Duration.ZERO,
    public val keepAlive: Duration = DEFAULT_KEEP_ALIVE,
    public val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    public val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
    public val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
) {
    init {
        require(host.isNotEmpty()) { "host が空です" }
        require(port in 1..MAX_PORT) { "port が範囲外です: $port" }
        require(clientId.length <= MAX_UTF8_STRING) { "clientId が長すぎます" }
        require(password == null || username != null) { "password だけを送ることはできません(ユーザー名も指定してください)" }
        require(!sessionExpiry.isNegative() && sessionExpiry.inWholeSeconds <= UINT32_MAX) { "sessionExpiry が範囲外です" }
        require(!keepAlive.isNegative() && keepAlive.inWholeSeconds <= UINT16_MAX) { "keepAlive が範囲外です" }
        require(connectTimeout.isPositive() && ackTimeout.isPositive()) { "タイムアウトは正の値にしてください" }
        require(maxInFlight in 1..UINT16_MAX) { "maxInFlight が範囲外です" }
    }

    override fun toString(): String = "MqttConfig(host=$host, port=$port, clientId=$clientId, username=$username)"

    public companion object {
        public val DEFAULT_KEEP_ALIVE: Duration = 30.seconds
        public val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        public val DEFAULT_ACK_TIMEOUT: Duration = 10.seconds
        public const val DEFAULT_MAX_IN_FLIGHT: Int = 20
        private const val MAX_PORT = 65535
        internal const val UINT16_MAX = 65535
        internal const val UINT32_MAX = 4294967295L
        internal const val MAX_UTF8_STRING = 65535
    }
}
