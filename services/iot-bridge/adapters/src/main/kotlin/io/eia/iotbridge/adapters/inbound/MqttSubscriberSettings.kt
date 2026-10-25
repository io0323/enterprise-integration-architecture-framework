package io.eia.iotbridge.adapters.inbound

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * iot-bridge の MQTT の購読の設定(ADR-0039 §2・§3・§9)。ブリッジは専用のユーザーで、`devices/+/+/telemetry` の読み取りだけを許される。
 *
 * @param sessionExpiry 切断した後に Broker がセッション(溜め込み)を残す時間。耐えられる停止の時間(約 29 分)より長くする(§9)
 * @param receiveMaximum 同時に受け取る数(PUBACK を返していないもの)。まとめて届いたときの背圧(§8)
 */
@Suppress("LongParameterList") // 設定の項目(既定値つき。名前付き引数で指定する)
public class MqttSubscriberSettings(
    public val host: String,
    public val port: Int,
    public val clientId: String,
    public val username: String,
    private val password: String,
    public val sessionExpiry: Duration = DEFAULT_SESSION_EXPIRY,
    public val receiveMaximum: Int = DEFAULT_RECEIVE_MAXIMUM,
) {
    init {
        require(host.isNotBlank() && clientId.isNotBlank() && username.isNotBlank()) { "host・clientId・username は空にできません" }
        require(password.isNotEmpty()) { "password が空です" }
        require(receiveMaximum in 1..MAX_RECEIVE) { "receiveMaximum が範囲外です" }
    }

    /** パスワード。CONNECT を送る直前にだけ使う。 */
    internal fun revealPassword(): ByteArray = password.toByteArray(Charsets.UTF_8)

    override fun toString(): String = "MqttSubscriberSettings(host=$host, port=$port, clientId=$clientId, username=$username)"

    public companion object {
        public val DEFAULT_SESSION_EXPIRY: Duration = 24.hours
        public const val DEFAULT_RECEIVE_MAXIMUM: Int = 100
        private const val MAX_RECEIVE = 65535
    }
}
