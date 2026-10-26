package io.eia.iotbridge.adapters.inbound

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import io.eia.shared.kernel.RetryPolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * MQTT Broker のテレメトリを購読し、1 件ずつ [TelemetryMessageHandler] で処理する(HiveMQ MQTT Client。ADR-0038 §3・ADR-0039 §2)。
 *
 * - **手動の ack**: PUBACK は、処理(Kafka への発行か DLQ への隔離)が確定してから返す。返す前に落ちたら Broker が送り直す
 *   (重複は ce_id で消費者が取り除く。§5)。
 * - **永続セッション**(clean start = false・セッションの期限 24 時間): iot-bridge が止まっている間、Broker がメッセージを溜める(§9)。
 *   受け取りの処理は、接続の前に登録する(再接続の直後に届く溜め込みを取りこぼさないため)。前のインスタンスの購読で溜まったものは、
 *   新しいクライアントからは「購読していないもの」に見えるので、すべての PUBLISH を受け取る(購読は `devices/+/+/telemetry` だけで、
 *   ACL でもそれしか読めない)。切れたら自動で再接続する。
 * - **順序**: 届いた順に 1 件ずつ処理する(デバイスごとの順序を保つ。§6)。Kafka に送れないとき([HandlingResult.RETRY])は、
 *   PUBACK を返さずに、kernel の RetryPolicy の待ち時間(上限 30 秒)の後に同じメッセージを処理し直す。その間は次に進まず、
 *   受け取りきれない分は Broker に溜まる(背圧。Receive Maximum)。
 * - `receivedAt` は、メッセージを受け取った時刻(処理を待った時間を含めない。§7)。
 */
public class HiveMqTelemetrySubscriber(
    private val settings: MqttSubscriberSettings,
    private val handler: TelemetryMessageHandler,
    private val clock: Clock = Clock.System,
    private val retry: RetryPolicy = RETRY,
    private val random: Random = Random.Default,
) {
    private val queue = Channel<Received>(Channel.UNLIMITED)

    private val client: Mqtt5AsyncClient =
        Mqtt5Client
            .builder()
            .identifier(settings.clientId)
            .serverHost(settings.host)
            .serverPort(settings.port)
            .automaticReconnect()
            .initialDelay(RECONNECT_INITIAL_SECONDS, TimeUnit.SECONDS)
            .maxDelay(RECONNECT_MAX_SECONDS, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .simpleAuth()
            .username(settings.username)
            .password(settings.revealPassword())
            .applySimpleAuth()
            .buildAsync()

    @Volatile
    private var subscribed = false

    /** Broker につながっているか(ヘルスチェック)。 */
    public val isConnected: Boolean get() = client.state.isConnected

    /** 購読を始めたか(受け取れる状態か。準備のチェック)。 */
    public val isSubscribed: Boolean get() = subscribed && isConnected

    /**
     * 接続して購読し、キャンセルされるまで処理する。最初の接続か購読に失敗したら、切断して(自動の再接続も止めて)例外で終わる。
     * やり直すときは、新しいインスタンスで呼ぶ(1 つのインスタンスで呼ぶのは 1 回だけ)。
     */
    public suspend fun run() {
        try {
            client.publishes(MqttGlobalPublishFilter.ALL, { queue.trySend(Received(it, clock.now())) }, true)
            client
                .connectWith()
                .cleanStart(false)
                .sessionExpiryInterval(settings.sessionExpiry.inWholeSeconds)
                .restrictions()
                .receiveMaximum(settings.receiveMaximum)
                .applyRestrictions()
                .send()
                .await()
            client
                .subscribeWith()
                .topicFilter(TelemetryMessageHandler.SUBSCRIPTION)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .await()
            subscribed = true
            logger.info("MQTT の購読を始めました topic={}", TelemetryMessageHandler.SUBSCRIPTION)
            for (received in queue) process(received)
        } finally {
            subscribed = false
            client.disconnect()
        }
    }

    private suspend fun process(received: Received) {
        val message =
            ReceivedMqttMessage(
                topic = received.publish.topic.toString(),
                payload = received.publish.payloadAsBytes,
                userProperties =
                    received.publish.userProperties
                        .asList()
                        .map { it.name.toString() to it.value.toString() },
                receivedAt = received.at,
            )
        var attempt = 1
        while (handler.handle(message) == HandlingResult.RETRY) {
            delay(retry.backoff(attempt++, random))
        }
        received.publish.acknowledge()
    }

    private class Received(
        val publish: Mqtt5Publish,
        val at: Instant,
    )

    public companion object {
        /** Kafka に送れないときの待ち時間(1 秒から倍々で上限 30 秒。Full Jitter)。回数は数えない(送れるまで待つ)。 */
        public val RETRY: RetryPolicy = RetryPolicy(initialDelay = 1.seconds, maxDelay = 30.seconds)

        private const val RECONNECT_INITIAL_SECONDS = 1L
        private const val RECONNECT_MAX_SECONDS = 30L
        private val logger = LoggerFactory.getLogger(HiveMqTelemetrySubscriber::class.java)
    }
}
