package io.eia.shared.sdk.mqtt

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * デバイスの側から MQTT Broker にメッセージを送る(ADR-0038 §3)。接続は必要になったときに張り、切れたら次の送信で張り直す。
 *
 * - 1 回の送信を [resilience] で包む。一時的な失敗([MqttUnavailable]: 接続の失敗・切断・PUBACK のタイムアウト・Broker が混んでいる)は、
 *   kernel の RetryPolicy の待ち時間(Backoff + Jitter)の後に、再接続して送り直す(shared/resilience。同じ処理を SDK に作らない)。
 * - 送り直しは新しい PUBLISH になる(MQTT の DUP ではない)。PUBACK を受け取る前に切れた場合、Broker に届いていれば重複する。
 *   ペイロードの `boot`・`seq` が同じなので、受け手(iot-bridge)は同じ ce_id にし、消費者が取り除く(ADR-0039 §5)。
 * - 拒否([MqttConnectRejected]・[MqttPublishRejected]。資格情報の誤り・ACL)は送り直さない。
 *
 * @param resilience 既定は [DEFAULT_RESILIENCE](試行 30 秒・初回を含めて 5 回・待ち時間は 1 秒から倍々で上限 1 分)
 */
public class MqttPublisher(
    private val config: MqttConfig,
    private val resilience: Resilience = Resilience(DEFAULT_RESILIENCE_NAME, DEFAULT_RESILIENCE),
) {
    private val lock = Mutex()
    private var connection: MqttConnection? = null

    /** [message] を送る。QoS 1 なら PUBACK の理由のコードを返す。 */
    public suspend fun publish(message: MqttMessage): Result<PublishResult, DomainError> =
        resilience.execute {
            connection().flatMap { current -> current.publish(message).mapError(MqttError::asDomainError) }
        }

    /** 接続していれば、DISCONNECT を送って切る。 */
    public suspend fun close() {
        lock.withLock {
            connection?.disconnect()
            connection = null
        }
    }

    private suspend fun connection(): Result<MqttConnection, DomainError> =
        lock.withLock {
            val current = connection?.takeIf { it.isOpen }
            if (current != null) {
                ok(current)
            } else {
                MqttConnection
                    .open(config)
                    .map { opened -> opened.also { connection = it } }
                    .mapError(MqttError::asDomainError)
            }
        }

    public companion object {
        /** 既定の [Resilience] の名前。 */
        public const val DEFAULT_RESILIENCE_NAME: String = "mqtt-broker"

        /**
         * 既定の回復性の設定。1 回の試行(接続と PUBACK の待ち)は 30 秒、初回を含めて 5 回、待ち時間は 1 秒から倍々で上限 1 分(Full Jitter)。
         * Circuit Breaker は使わない(デバイスは Broker のほかに送り先がなく、送れない間は呼び出し側が溜める。ADR-0039 §8)。
         */
        public val DEFAULT_RESILIENCE: ResilienceConfig =
            ResilienceConfig(
                attemptTimeout = 30.seconds,
                retry = RetryPolicy(initialDelay = 1.seconds, maxAttempts = 5, maxDelay = 1.minutes),
                retryBudget = null,
                circuitBreaker = null,
            )
    }
}
