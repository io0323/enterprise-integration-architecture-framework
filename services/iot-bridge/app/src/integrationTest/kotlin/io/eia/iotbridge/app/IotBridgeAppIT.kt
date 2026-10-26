@file:Suppress("MagicNumber") // 待ち時間・件数・ポート

package io.eia.iotbridge.app

import io.eia.iotbridge.adapters.inbound.DevicePayloadDecoder
import io.eia.iotbridge.adapters.inbound.MessageIds
import io.eia.iotbridge.adapters.outbound.IotEventSchemas
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.ReplayCommandLine
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.trace.TraceParent
import io.eia.shared.sdk.mqtt.MqttConfig
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.MqttPassword
import io.eia.shared.sdk.mqtt.MqttPublisher
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.kafka.KafkaContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import java.net.http.HttpClient as JdkHttpClient

private const val MQTT_PORT = 1883
private const val TELEMETRY = "iot.telemetry.reported.v1"
private const val DLQ = "$TELEMETRY.dlq"
private const val BOOT = "cccccccccccccccc"

private val MOSQUITTO_CONFIG =
    """
    listener $MQTT_PORT
    protocol mqtt
    allow_anonymous false
    password_file /mosquitto/passwd
    acl_file /mosquitto/acl
    retain_available false
    """.trimIndent()

private val MOSQUITTO_ACL =
    """
    user t1/d-001
    topic write devices/t1/d-001/#

    user iot-bridge
    topic read devices/+/+/telemetry
    """.trimIndent()

private const val MOSQUITTO_STARTUP =
    "mosquitto_passwd -b -c /mosquitto/passwd t1/d-001 pw-1 && mosquitto_passwd -b /mosquitto/passwd iot-bridge pw-b && " +
        "cp /mosquitto/acl.src /mosquitto/acl && chown mosquitto:mosquitto /mosquitto/passwd /mosquitto/acl && " +
        "chmod 0700 /mosquitto/passwd /mosquitto/acl && exec mosquitto -c /mosquitto/config/mosquitto.conf"

private class Mosquitto : GenericContainer<Mosquitto>(InfraImages.get("MOSQUITTO_IMAGE"))

private fun v2(seq: Long) =
    """{"v":2,"boot":"$BOOT","seq":$seq,"time":"2026-10-26T00:00:00Z","readings":[{"name":"temperature","value":20.5}]}"""

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

/**
 * iot-bridge の app の全体(ADR-0039): serve の準備の条件(スキーマ ID・Kafka のトピック・MQTT の購読)と、DLQ の再処理(reprocess-dlq)。
 * 実際の Mosquitto(ACL つき)・Kafka・Apicurio Registry で確かめる。native のシミュレータからの確認と compose での動きは P11 ⑥・⑦。
 */
class IotBridgeAppIT :
    FunSpec({
        val mosquitto =
            Mosquitto()
                .withCopyToContainer(Transferable.of(MOSQUITTO_CONFIG), "/mosquitto/config/mosquitto.conf")
                .withCopyToContainer(Transferable.of(MOSQUITTO_ACL), "/mosquitto/acl.src")
                .withCreateContainerCmdModifier { it.withEntrypoint("sh", "-c", MOSQUITTO_STARTUP) }
                .withExposedPorts(MQTT_PORT)
                .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1))
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val registry = ApicurioRegistryContainer()
        val http = JdkHttpClient.newHttpClient()
        lateinit var server: IotBridgeServer
        lateinit var device: MqttPublisher
        lateinit var consumer: KafkaConsumer<ByteArray, ByteArray>
        lateinit var env: Map<String, String>

        fun ready(port: Int): Int =
            http
                .send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()

        suspend fun awaitReady(
            port: Int,
            status: Int,
        ) = withTimeout(90.seconds) { while (ready(port) != status) delay(500) }

        /** [topic] の新しいレコードを [count] 件まで(待ちの上限まで)読む。 */
        fun poll(
            topic: String,
            count: Int,
        ): List<ConsumerRecord<ByteArray, ByteArray>> {
            consumer.subscribe(listOf(topic))
            val records = mutableListOf<ConsumerRecord<ByteArray, ByteArray>>()
            val deadline = System.nanoTime() + 60.seconds.inWholeNanoseconds
            while (records.size < count && System.nanoTime() < deadline) {
                records += consumer.poll(Duration.ofMillis(500)).records(topic)
            }
            consumer.commitSync()
            consumer.unsubscribe()
            return records
        }

        fun reprocess(
            args: List<String>,
            decoder: DevicePayloadDecoder = DevicePayloadDecoder(),
        ): Pair<Int, String> {
            val buffer = ByteArrayOutputStream()
            val code = ReprocessDlqCommand(decoder).run(listOf("--limit", "10") + args, env, PrintStream(buffer, true, Charsets.UTF_8))
            return code to buffer.toString(Charsets.UTF_8)
        }

        beforeSpec {
            listOf(mosquitto, kafka, registry).parallelStream().forEach { it.start() }
            env =
                mapOf(
                    IotBridgeConfig.KAFKA_BOOTSTRAP to kafka.bootstrapServers,
                    IotBridgeConfig.SCHEMA_REGISTRY_URL to registry.baseUrl,
                    IotBridgeConfig.MQTT_HOST to mosquitto.host,
                    "IOT_BRIDGE_MQTT_PORT" to mosquitto.getMappedPort(MQTT_PORT).toString(),
                    "IOT_BRIDGE_MQTT_PASSWORD" to "pw-b",
                    "IOT_BRIDGE_HEALTH_PORT" to "0",
                )
            consumer =
                KafkaConsumer(
                    mapOf(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG to "iot-bridge-app-it.reader",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                    ),
                    ByteArrayDeserializer(),
                    ByteArrayDeserializer(),
                )
            device =
                MqttPublisher(
                    MqttConfig(
                        host = mosquitto.host,
                        port = mosquitto.getMappedPort(MQTT_PORT),
                        clientId = "t1-d-001",
                        username = "t1/d-001",
                        password = MqttPassword("pw-1"),
                    ),
                    Resilience("device", ResilienceConfig(attemptTimeout = 10.seconds, circuitBreaker = null, retryBudget = null)),
                )
        }

        afterSpec {
            server.stop()
            device.close()
            consumer.close()
            listOf(mosquitto, kafka, registry).forEach { it.stop() }
        }

        test("ready は、スキーマ ID の解決・書くトピックの確認・MQTT の購読がそろうまで DOWN。そろえば UP") {
            // 形式の版 1 だけを読むデコーダ(版 2 のデコーダを足す前のブリッジ)
            server = IotBridgeServer.start(env, DevicePayloadDecoder(setOf(1))).ok()
            val port = server.healthPort

            ready(port) shouldBe 503
            delay(2.seconds)
            ready(port) shouldBe 503

            HttpClient(CIO).use { client ->
                val registryClient = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl), client)
                IotEventSchemas.subjects.forEach { registryClient.register(it).ok() }
            }
            // スキーマ ID は解決できても、トピックがなければ DOWN のまま
            delay(6.seconds)
            ready(port) shouldBe 503

            Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(NewTopic(TELEMETRY, 3, 1), NewTopic(DLQ, 1, 1))).all().get()
            }
            awaitReady(port, 200)
        }

        test("デコーダを足した後に reprocess-dlq で DLQ をデコードからやり直す。dry-run が既定で、ce_id・受け取った時刻・トレースを引き継ぐ") {
            val parent = TraceParent.generate(Random(7))
            device
                .publish(
                    MqttMessage(
                        "devices/t1/d-001/telemetry",
                        v2(1).encodeToByteArray(),
                        contentType = "application/json",
                    ).withTrace(parent),
                ).ok()
            val dead = poll(DLQ, 1).single()
            val headers = dead.headers().associate { it.key() to String(it.value()) }
            headers["eiaf.dlq.reason"] shouldBe "UNSUPPORTED_PAYLOAD_VERSION"
            val receivedAt = Instant.parse(headers.getValue("eiaf.mqtt.received-at"))

            // デコーダを足す前: 読めない見込み(REJECTED)で 1
            reprocess(emptyList(), DevicePayloadDecoder(setOf(1))).let { (code, output) ->
                code shouldBe ReplayCommandLine.PARTIAL
                output shouldContain "REJECTED 1"
                output shouldContain "dry-run"
            }
            // 足した後の dry-run: 読める見込み(PLANNED)で 0。送らない
            reprocess(emptyList()).let { (code, output) ->
                code shouldBe ReplayCommandLine.OK
                output shouldContain "PLANNED 1"
            }
            poll(TELEMETRY, 1).size shouldBe 0

            reprocess(listOf("--execute")).let { (code, output) ->
                code shouldBe ReplayCommandLine.OK
                output shouldContain "REPLAYED 1"
                output.contains("cccccccccccccccc") shouldBe false
            }
            val record = poll(TELEMETRY, 1).single()
            String(record.key()) shouldBe "t1/d-001"
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata.id shouldBe MessageIds.nameBased(MessageIds.NAMESPACE, "t1/d-001/$BOOT/1")
            metadata.time shouldBe receivedAt
            metadata.traceParent.traceId shouldBe parent.traceId
            metadata.correlationId.value shouldBe headers.getValue("correlationid")

            // DLQ のレコードは残る(消さない)。もう一度実行しても同じ ce_id なので、消費者は 1 回だけ処理する
            reprocess(listOf("--execute")).first shouldBe ReplayCommandLine.OK
            EventMetadata.fromHeaders(poll(TELEMETRY, 1).single().headers()).ok().id shouldBe metadata.id
        }

        test("Broker に接続できない(資格情報の誤り)間は ready が DOWN のまま、やり直し続ける(プロセスは落ちない)") {
            val wrong =
                IotBridgeServer
                    .start(env + mapOf("IOT_BRIDGE_MQTT_PASSWORD" to "wrong", "IOT_BRIDGE_MQTT_CLIENT_ID" to "iot-bridge-wrong"))
                    .ok()
            try {
                runBlocking { delay(12.seconds) }
                ready(wrong.healthPort) shouldBe 503
                http
                    .send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:${wrong.healthPort}/health/live")).GET().build(),
                        HttpResponse.BodyHandlers.discarding(),
                    ).statusCode() shouldBe 200
            } finally {
                wrong.stop()
            }
        }
    })
