@file:Suppress("MagicNumber") // 件数・待ち時間・MQTT の理由のコード

package io.eia.iotbridge.adapters

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAckReasonCode
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.sdk.mqtt.MqttConfig
import io.eia.shared.sdk.mqtt.MqttConnectRejected
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.MqttPassword
import io.eia.shared.sdk.mqtt.MqttPublishRejected
import io.eia.shared.sdk.mqtt.MqttPublisher
import io.eia.shared.sdk.mqtt.PublishResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val MQTT_PORT = 1883
private const val QUEUE_LIMIT = 5
private const val BRIDGE_PASSWORD = "pw-bridge"
private const val MONITOR_PASSWORD = "pw-monitor"
private val INFRA: Path = Path.of(checkNotNull(System.getProperty("eia.infraLocal")) { "Gradle の integrationTest タスクから実行してください" })

private class Broker : GenericContainer<Broker>(InfraImages.get("MOSQUITTO_IMAGE"))

/**
 * ローカル基盤の Mosquitto の設定(infra/local/mosquitto/mosquitto.conf)と起動のスクリプト(start.sh)を、そのまま実際の Mosquitto で確かめる
 * (P11 ⑥。ADR-0039 §3・§9)。溜め込みの上限だけを小さくし($SYS の間隔も短くし)、上限を超えたときに本当に何が起きるかを見る。
 */
class MosquittoBrokerIT :
    FunSpec({
        val config =
            INFRA
                .resolve("mosquitto/mosquitto.conf")
                .readText()
                .replace(Regex("(?m)^max_queued_messages .*$"), "max_queued_messages $QUEUE_LIMIT") + "\nsys_interval 1\n"
        val broker =
            Broker()
                .withCopyToContainer(Transferable.of(config), "/mosquitto/config/mosquitto.conf")
                .withCopyToContainer(Transferable.of(INFRA.resolve("mosquitto/start.sh").readText()), "/mosquitto/start.sh")
                .withCopyToContainer(Transferable.of("# テストのデバイス\nt1/d-001\nt1/d-002\n"), "/mosquitto/devices.txt")
                .withCopyToContainer(Transferable.of("t1/d-001 pw-1\nt1/d-002 pw-2\n"), "/mosquitto/secrets/device-passwords")
                .withEnv("MQTT_BRIDGE_PASSWORD", BRIDGE_PASSWORD)
                .withEnv("MQTT_MONITOR_PASSWORD", MONITOR_PASSWORD)
                .withCreateContainerCmdModifier { it.withEntrypoint("sh", "/mosquitto/start.sh") }
                .withExposedPorts(MQTT_PORT)
                .waitingFor(Wait.forListeningPort())

        fun device(
            username: String,
            password: String,
        ) = MqttPublisher(
            MqttConfig(
                host = broker.host,
                port = broker.getMappedPort(MQTT_PORT),
                clientId = username.replace('/', '-'),
                username = username,
                password = MqttPassword(password),
            ),
            Resilience("device", ResilienceConfig(attemptTimeout = 10.seconds, retry = null, circuitBreaker = null, retryBudget = null)),
        )

        fun client(
            clientId: String,
            username: String,
            password: String,
        ): Mqtt5BlockingClient =
            Mqtt5Client
                .builder()
                .identifier(clientId)
                .serverHost(broker.host)
                .serverPort(broker.getMappedPort(MQTT_PORT))
                .simpleAuth()
                .username(username)
                .password(password.toByteArray())
                .applySimpleAuth()
                .buildBlocking()

        /**
         * 監視のユーザーで $SYS の [topic] の値を読む(1 秒ごとに更新される)。[accept] を満たすまで [wait] だけ待つ。
         * まだ出ていない値(捨てた数は 1 件目を捨てるまで出ない)は -1。
         */
        fun sys(
            topic: String,
            wait: Duration = 20.seconds,
            accept: (Long) -> Boolean,
        ): Long {
            val monitor = client("monitor-${System.nanoTime()}", "eiaf-monitor", MONITOR_PASSWORD)
            monitor.publishes(MqttGlobalPublishFilter.ALL).use { publishes ->
                monitor.connect()
                monitor
                    .subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_MOST_ONCE)
                    .send()
                val deadline = System.nanoTime() + wait.inWholeNanoseconds
                var last = -1L
                while (System.nanoTime() < deadline && !(last >= 0 && accept(last))) {
                    publishes.receive(1, TimeUnit.SECONDS).ifPresent { last = String(it.payloadAsBytes).trim().toLong() }
                }
                monitor.disconnect()
                return last
            }
        }

        beforeSpec { broker.start() }
        afterSpec { broker.stop() }

        context("デバイスの一覧から作った資格情報と ACL(ADR-0039 §3)") {
            test("デバイスは自分のトピックにだけ書ける(他のデバイスのトピックは PUBACK 0x87)") {
                val publisher = device("t1/d-001", "pw-1")
                try {
                    publisher
                        .publish(
                            MqttMessage("devices/t1/d-001/telemetry", "{}".toByteArray()),
                        ).shouldBeInstanceOf<Result.Ok<PublishResult>>()
                    publisher
                        .publish(MqttMessage("devices/t1/d-002/telemetry", "{}".toByteArray()))
                        .shouldBeInstanceOf<Result.Err<*>>()
                        .error shouldBe MqttPublishRejected(0x87)
                } finally {
                    publisher.close()
                }
            }

            test("一覧にないデバイス・誤ったパスワードは接続できない") {
                device("t1/d-999", "pw-1")
                    .publish(MqttMessage("devices/t1/d-999/telemetry", "{}".toByteArray()))
                    .shouldBeInstanceOf<Result.Err<*>>()
                    .error
                    .shouldBeInstanceOf<MqttConnectRejected>()
                device("t1/d-002", "wrong")
                    .publish(MqttMessage("devices/t1/d-002/telemetry", "{}".toByteArray()))
                    .shouldBeInstanceOf<Result.Err<*>>()
                    .error
                    .shouldBeInstanceOf<MqttConnectRejected>()
            }

            test("iot-bridge は読むだけ(書けない)。監視のユーザーは \$SYS を読める") {
                val bridge = client("bridge-acl", "iot-bridge", BRIDGE_PASSWORD)
                bridge.connect()
                shouldThrow<Mqtt5PubAckException> {
                    bridge
                        .publishWith()
                        .topic("devices/t1/d-001/telemetry")
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .payload("{}".toByteArray())
                        .send()
                }.mqttMessage.reasonCode shouldBe Mqtt5PubAckReasonCode.NOT_AUTHORIZED
                bridge.disconnect()
                (sys("\$SYS/broker/clients/connected") { it >= 0 } >= 0) shouldBe true
            }

            test("匿名接続は拒否する") {
                shouldThrow<Mqtt5ConnAckException> {
                    Mqtt5Client
                        .builder()
                        .serverHost(broker.host)
                        .serverPort(broker.getMappedPort(MQTT_PORT))
                        .buildBlocking()
                        .connect()
                }
            }
        }

        test("溜め込みの上限を超えると、デバイスには PUBACK が返るのに、超えた分は捨てられる(ADR-0039 §9)") {
            // iot-bridge と同じ永続セッションで購読してから切断する(Broker が溜め込む)
            val bridge = client("iot-bridge-limit", "iot-bridge", BRIDGE_PASSWORD)
            bridge
                .connectWith()
                .cleanStart(true)
                .sessionExpiryInterval(3600)
                .send()
            bridge
                .subscribeWith()
                .topicFilter("devices/+/+/telemetry")
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
            bridge.disconnect()
            val droppedBefore = sys("\$SYS/broker/publish/messages/dropped", wait = 3.seconds) { true }.coerceAtLeast(0)

            // 上限の 2 倍を送る。デバイスにはすべて PUBACK が返る(Broker は受け取ってから、ブリッジのセッションの分を捨てる)
            val publisher = device("t1/d-001", "pw-1")
            val results =
                try {
                    (0 until QUEUE_LIMIT * 2).map {
                        publisher.publish(MqttMessage("devices/t1/d-001/telemetry", """{"seq":$it}""".toByteArray()))
                    }
                } finally {
                    publisher.close()
                }
            results.forEach { it.shouldBeInstanceOf<Result.Ok<PublishResult>>() }

            // 捨てた数($SYS)が上限を超えた分だけ増える
            sys("\$SYS/broker/publish/messages/dropped") { it >= droppedBefore + QUEUE_LIMIT } shouldBe droppedBefore + QUEUE_LIMIT

            // ブリッジが戻ると、溜めていた先頭の上限の数だけが届く(後から来た分が捨てられている)
            val resumed = client("iot-bridge-limit", "iot-bridge", BRIDGE_PASSWORD)
            val received =
                resumed.publishes(MqttGlobalPublishFilter.ALL).use { publishes ->
                    resumed
                        .connectWith()
                        .cleanStart(false)
                        .sessionExpiryInterval(3600)
                        .send()
                        .isSessionPresent shouldBe true
                    generateSequence { publishes.receive(3, TimeUnit.SECONDS).orElse(null) }
                        .map { String(it.payloadAsBytes) }
                        .toList()
                }
            resumed.disconnect()
            received shouldBe (0 until QUEUE_LIMIT).map { """{"seq":$it}""" }
        }
    })
