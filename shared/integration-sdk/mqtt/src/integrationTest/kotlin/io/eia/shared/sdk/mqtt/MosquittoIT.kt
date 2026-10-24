@file:Suppress("MagicNumber") // MQTT v5 の理由のコード・待ち時間

package io.eia.shared.sdk.mqtt

import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val PORT = 1883

/** デバイスごとの資格情報と ACL(ADR-0039 §3。ローカル基盤の設定は P11 ⑥)。 */
private val CONFIG =
    """
    listener $PORT
    protocol mqtt
    allow_anonymous false
    password_file /mosquitto/passwd
    acl_file /mosquitto/acl
    retain_available false
    """.trimIndent()

private val ACL =
    """
    user t1/d-001
    topic write devices/t1/d-001/#

    user t1/d-002
    topic write devices/t1/d-002/#

    user reader
    topic read devices/#
    """.trimIndent()

private const val STARTUP =
    "mosquitto_passwd -b -c /mosquitto/passwd t1/d-001 pw-1 && mosquitto_passwd -b /mosquitto/passwd t1/d-002 pw-2 && " +
        "mosquitto_passwd -b /mosquitto/passwd reader pw-r && cp /mosquitto/acl.src /mosquitto/acl && " +
        "chown mosquitto:mosquitto /mosquitto/passwd /mosquitto/acl && chmod 0700 /mosquitto/passwd /mosquitto/acl && " +
        "exec mosquitto -c /mosquitto/config/mosquitto.conf"

private class Mosquitto : GenericContainer<Mosquitto>(InfraImages.get("MOSQUITTO_IMAGE"))

private fun mosquitto(): Mosquitto =
    Mosquitto()
        .withCopyToContainer(Transferable.of(CONFIG), "/mosquitto/config/mosquitto.conf")
        .withCopyToContainer(Transferable.of(ACL), "/mosquitto/acl.src")
        // パスワードのファイルを起動のたびに作る(ローカル基盤の compose と同じ。所有者 mosquitto・0700 が要る)。
        // withCommand(String) は空白で分けてしまうので、スクリプトは entrypoint に 1 つの引数で渡す
        .withCreateContainerCmdModifier { it.withEntrypoint("sh", "-c", STARTUP) }
        .withExposedPorts(PORT)
        .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1))

private fun Mosquitto.publisher(
    username: String,
    password: String,
) = MqttPublisher(
    MqttConfig(
        host = host,
        port = getMappedPort(PORT),
        clientId = username.replace('/', '-'),
        username = username,
        password = MqttPassword(password),
    ),
    Resilience(
        "mqtt-it",
        ResilienceConfig(
            attemptTimeout = 10.seconds,
            retry = RetryPolicy(initialDelay = 100.milliseconds, maxAttempts = 2, jitter = Jitter.NONE),
            retryBudget = null,
            circuitBreaker = null,
        ),
    ),
)

/** 実際の Mosquitto(images.env の MOSQUITTO_IMAGE)に、SDK の MQTT v5 の最小限のクライアントで送る(ADR-0038)。 */
class MosquittoIT :
    FunSpec({
        val broker = mosquitto()

        beforeSpec { broker.start() }
        afterSpec { broker.stop() }

        test("QoS 1 で送ると PUBACK(Success か No matching subscribers)が返り、購読者に traceparent のユーザープロパティが届く") {
            val publisher = broker.publisher("t1/d-001", "pw-1")
            val parent = TraceParent.generate(Random(7))
            val message = MqttMessage("devices/t1/d-001/telemetry", """{"v":2,"boot":"0123456789abcdef","seq":1}""".encodeToByteArray())
            try {
                val subscriber =
                    async(Dispatchers.IO) {
                        broker.execInContainer(
                            "mosquitto_sub",
                            "-h",
                            "127.0.0.1",
                            "-u",
                            "reader",
                            "-P",
                            "pw-r",
                            "-V",
                            "mqttv5",
                            "-t",
                            "devices/#",
                            "-C",
                            "1",
                            "-W",
                            "15",
                            "-F",
                            "%t %P %p",
                        )
                    }
                // 購読が始まるまで送り続ける(購読の前のメッセージは retain しないので届かない)
                withTimeout(15.seconds) {
                    while (!subscriber.isCompleted) {
                        publisher.publish(message.withTrace(parent)).shouldBeInstanceOf<Result.Ok<PublishResult>>()
                        delay(200.milliseconds)
                    }
                }
                val output = subscriber.await().stdout
                output shouldContain "devices/t1/d-001/telemetry"
                output shouldContain "traceparent:00-${parent.traceId}-"
                output shouldContain "correlationid:"
                output shouldContain """"seq":1"""
            } finally {
                publisher.close()
            }
        }

        test("ACL で書けない他のデバイスのトピックは、PUBACK 0x87(Not authorized)で拒否され、送り直さない") {
            val publisher = broker.publisher("t1/d-001", "pw-1")
            try {
                publisher
                    .publish(
                        MqttMessage("devices/t1/d-002/telemetry", "{}".encodeToByteArray()),
                    ).shouldBeInstanceOf<Result.Err<DomainError>>()
                    .error shouldBe MqttPublishRejected(0x87)
            } finally {
                publisher.close()
            }
        }

        // MQTT v5 には 0x86(Bad User Name or Password)もあるが、Mosquitto 2.1 はパスワードの誤りに 0x87(Not authorized)を返す
        test("パスワードの誤りは CONNACK 0x87(Mosquitto は Not authorized)で拒否され、送り直さない") {
            broker
                .publisher("t1/d-001", "wrong")
                .publish(MqttMessage("devices/t1/d-001/telemetry", "{}".encodeToByteArray()))
                .shouldBeInstanceOf<Result.Err<DomainError>>()
                .error shouldBe MqttConnectRejected(0x87)
        }
    })
