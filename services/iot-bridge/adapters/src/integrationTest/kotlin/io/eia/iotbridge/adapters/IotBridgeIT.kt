@file:Suppress("MagicNumber") // 件数・待ち時間・MQTT の理由のコード

package io.eia.iotbridge.adapters

import io.eia.iotbridge.adapters.inbound.DevicePayloadDecoder
import io.eia.iotbridge.adapters.inbound.HiveMqTelemetrySubscriber
import io.eia.iotbridge.adapters.inbound.MessageIds
import io.eia.iotbridge.adapters.inbound.MqttSubscriberSettings
import io.eia.iotbridge.adapters.inbound.TelemetryMessageHandler
import io.eia.iotbridge.adapters.inbound.TelemetryMetrics
import io.eia.iotbridge.adapters.outbound.IotEventSchemas
import io.eia.iotbridge.adapters.outbound.KafkaTelemetryPublisher
import io.eia.iotbridge.application.usecase.IngestTelemetryService
import io.eia.platform.inbox.Inbox
import io.eia.platform.inbox.InboxSchema
import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaBrokerContainer
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.trace.TraceParent
import io.eia.shared.sdk.mqtt.MqttConfig
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.MqttPassword
import io.eia.shared.sdk.mqtt.MqttPublishRejected
import io.eia.shared.sdk.mqtt.MqttPublisher
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val MQTT_PORT = 1883
private const val TELEMETRY = "iot.telemetry.reported.v1"
private const val DLQ = "$TELEMETRY.dlq"
private const val BOOT_A = "aaaaaaaaaaaaaaaa"
private const val BOOT_B = "bbbbbbbbbbbbbbbb"

/** デバイスごとの資格情報と ACL(ADR-0039 §3)。ブリッジは読み取りだけ。 */
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

private fun v2(
    boot: String,
    seq: Long,
    time: String = "2026-10-25T00:00:00Z",
) = """{"v":2,"boot":"$boot","seq":$seq,"time":"$time","readings":[{"name":"temperature","value":20.5,"unit":"Cel"}],"battery":90}"""

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

/**
 * iot-bridge の橋渡しを、実際の Mosquitto(ACL つき)・Kafka・Apicurio Registry・PostgreSQL(消費者の platform/inbox)で確かめる(ADR-0039)。
 * デバイスは SDK の MQTT の部分(JVM)で送る。native のシミュレータからの確認は ⑦ の E2E。
 */
class IotBridgeIT :
    FunSpec({
        val mosquitto =
            Mosquitto()
                .withCopyToContainer(Transferable.of(MOSQUITTO_CONFIG), "/mosquitto/config/mosquitto.conf")
                .withCopyToContainer(Transferable.of(MOSQUITTO_ACL), "/mosquitto/acl.src")
                .withCreateContainerCmdModifier { it.withEntrypoint("sh", "-c", MOSQUITTO_STARTUP) }
                .withExposedPorts(MQTT_PORT)
                .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1))
        val kafka =
            KafkaBrokerContainer()
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val registry = ApicurioRegistryContainer()
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val spans = InMemorySpanExporter.create()
        val runtime =
            Observability.init(
                ObservabilityConfig.of("iot-bridge-it").getOrNull()!!,
                TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans))),
                installLogAppender = false,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var handler: TelemetryMessageHandler
        lateinit var bridge: Job
        lateinit var subscriber: HiveMqTelemetrySubscriber
        lateinit var device: MqttPublisher
        lateinit var consumer: KafkaConsumer<ByteArray, ByteArray>
        val schema = Schema.Parser().parse(IotEventSchemas.telemetryReported.schema)

        /** iot-bridge を動かし、購読を始めるまで待つ(購読の前に送ったものは、まだセッションがないので Broker が捨てる)。 */
        suspend fun startBridge(): Job {
            val settings =
                MqttSubscriberSettings(
                    host = mosquitto.host,
                    port = mosquitto.getMappedPort(MQTT_PORT),
                    clientId = "iot-bridge-it",
                    username = "iot-bridge",
                    password = "pw-b",
                )
            subscriber = HiveMqTelemetrySubscriber(settings, handler)
            val job = scope.launch { subscriber.run() }
            withTimeout(30.seconds) { while (!subscriber.isSubscribed) delay(100) }
            return job
        }

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

        fun value(record: ConsumerRecord<ByteArray, ByteArray>): GenericRecord {
            val framed = ApicurioWireFormat.parse(record.value()).ok()
            return GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(framed.avroBinary, null))
        }

        suspend fun send(
            payload: String,
            parent: TraceParent? = null,
        ) = device
            .publish(
                MqttMessage(
                    "devices/t1/d-001/telemetry",
                    payload.encodeToByteArray(),
                    contentType = "application/json",
                ).withTrace(parent),
            ).ok()

        beforeSpec {
            listOf(mosquitto, kafka, registry, postgres).parallelStream().forEach { it.start() }
            AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(NewTopic(TELEMETRY, 3, 1), NewTopic(DLQ, 1, 1))).all().get()
            }
            val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl), HttpClient(CIO))
            IotEventSchemas.subjects.forEach { client.register(it).ok() }
            val ids = SchemaIdBook(IotEventSchemas.subjects, client).also { it.resolve().ok() }
            producer =
                KafkaProducer(
                    KafkaProducerSettings(kafka.bootstrapServers, "iot-bridge-it").toProperties(),
                    ByteArraySerializer(),
                    ByteArraySerializer(),
                )
            handler =
                TelemetryMessageHandler(
                    IngestTelemetryService(KafkaTelemetryPublisher(EventProducer(producer, runtime, KafkaTelemetryPublisher.SOURCE), ids)),
                    DevicePayloadDecoder(),
                    DeadLetterPublisher(producer),
                    runtime,
                    TelemetryMetrics(runtime.meter),
                )
            consumer =
                KafkaConsumer(
                    mapOf(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG to "iot-bridge-it.reader",
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
            bridge = startBridge()
        }

        afterSpec {
            device.close()
            bridge.cancelAndJoin()
            consumer.close()
            producer.close()
            listOf(mosquitto, kafka, registry, postgres).forEach { it.stop() }
        }

        test("デバイスのテレメトリが Kafka に届く。キー・ce_id(UUID 版 5)・ce_time とレコードの時刻(受け取った時刻)・トレースを引き継ぐ") {
            val parent = TraceParent.generate(Random(1))
            val before = Instant.fromEpochMilliseconds(System.currentTimeMillis())

            send(v2(BOOT_A, 1), parent)

            val record = poll(TELEMETRY, 1).single()
            String(record.key()) shouldBe "t1/d-001"
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata.source shouldBe "/iot/iot-bridge"
            metadata.type shouldBe "iot.telemetry.reported"
            metadata.traceParent.traceId shouldBe parent.traceId
            metadata.id shouldBe MessageIds.nameBased(MessageIds.NAMESPACE, "t1/d-001/$BOOT_A/1")
            record.timestamp() shouldBe metadata.time.toEpochMilliseconds()
            (metadata.time >= before) shouldBe true

            val value = value(record)
            value.get("tenant").toString() shouldBe "t1"
            value.get("deviceId").toString() shouldBe "d-001"
            value.get("payloadVersion") shouldBe 2
            value.get("deviceTime") shouldBe 1_792_886_400_000_000L
            value.get("batteryPercent") shouldBe 90

            // CONSUMER の span はデバイスの traceparent の子、PRODUCER の span はその子(MQTT v5 のユーザープロパティでたどれる。DoD)
            val consumerSpan = spans.finishedSpanItems.single { it.kind == SpanKind.CONSUMER && it.traceId == parent.traceId.toString() }
            val producerSpan = spans.finishedSpanItems.single { it.kind == SpanKind.PRODUCER && it.traceId == parent.traceId.toString() }
            producerSpan.parentSpanId shouldBe consumerSpan.spanId
        }

        test("形式の版 1(古いファームウェア)も同じ形にそろえる") {
            send("""{"v":1,"boot":"$BOOT_A","seq":2,"ts":1792886400,"temp":21.5,"hum":40}""")

            val value = value(poll(TELEMETRY, 1).single())
            value.get("payloadVersion") shouldBe 1
            (value.get("readings") as List<*>).map { (it as GenericRecord).get("name").toString() } shouldBe
                listOf("temperature", "humidity")
        }

        test("番号が振り直されても(再起動で新しい boot)、正当な新しいデータは消費者の inbox で重複として捨てられない(承認の条件 1)") {
            // 起動 A の seq 0・同じものの再送(QoS 1 の再送の模擬)・再起動後の起動 B の seq 0
            send(v2(BOOT_A, 0))
            send(v2(BOOT_A, 0))
            send(v2(BOOT_B, 0, time = "2026-10-25T00:10:00Z"))

            val records = poll(TELEMETRY, 3)
            val ids = records.map { EventMetadata.fromHeaders(it.headers()).ok().id }
            ids[0] shouldBe ids[1]
            ids[2] shouldNotBe ids[0]

            // 消費者(platform/inbox。ADR-0028)で冪等に処理する
            val dataSource =
                PGSimpleDataSource().apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }
            InboxSchema.migrate(dataSource, postgres.username).ok()
            val receipts =
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    ids.map { Inbox().markProcessed(connection, "telemetry.consumer", it, TELEMETRY).ok() }.also { connection.commit() }
                }
            receipts shouldBe listOf(Inbox.Receipt.FIRST, Inbox.Receipt.DUPLICATE, Inbox.Receipt.FIRST)
        }

        test("知らない版は、受け取ったバイト列のまま DLQ に隔離し(PUBACK は返る)、その後のメッセージも止まらずに届く") {
            val unknown = """{"v":9,"boot":"$BOOT_A","seq":10}"""

            send(unknown)
            send(v2(BOOT_A, 11))

            val dead = poll(DLQ, 1).single()
            String(dead.value()) shouldBe unknown
            String(dead.key()) shouldBe "t1/d-001"
            dead
                .headers()
                .lastHeader("eiaf.dlq.reason")
                .value()
                .decodeToString() shouldBe "UNSUPPORTED_PAYLOAD_VERSION"
            dead
                .headers()
                .lastHeader("eiaf.mqtt.topic")
                .value()
                .decodeToString() shouldBe "devices/t1/d-001/telemetry"
            value(poll(TELEMETRY, 1).single()).get("seq") shouldBe 11L
        }

        test("iot-bridge が止まっている間に送られたものは Broker に溜まり(永続セッション)、戻ると届く") {
            bridge.cancelAndJoin()
            // 切断が Broker に届くまで待つ(届く前に送ると、まだ接続中のセッションに送られる)
            withTimeout(10.seconds) { while (subscriber.isConnected) delay(100) }

            for (seq in 20L..22L) send(v2(BOOT_A, seq))
            bridge = startBridge()

            poll(TELEMETRY, 3).map { value(it).get("seq") } shouldBe listOf(20L, 21L, 22L)
        }

        test("ACL で自分のトピックにしか書けない(他のデバイスのトピックは PUBACK 0x87)") {
            device
                .publish(MqttMessage("devices/t1/d-002/telemetry", v2(BOOT_A, 0).encodeToByteArray()))
                .shouldBeInstanceOf<Result.Err<*>>()
                .error shouldBe MqttPublishRejected(0x87)
        }
    })
