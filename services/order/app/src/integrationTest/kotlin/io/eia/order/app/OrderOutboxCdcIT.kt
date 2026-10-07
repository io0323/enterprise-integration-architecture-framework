@file:Suppress("MagicNumber") // 件数・待ち時間・ポート

package io.eia.order.app

import io.eia.order.adapters.out.outbox.OrderCreatedV1
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaConnectContainer
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

private const val TOPIC = "sales.order.created.v1"
private const val CONNECTOR = "order-outbox"
private const val SLOT_LSN = "SELECT confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = 'order_outbox'"

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val BODY =
    """
    {"customerId":"cust-1",
     "lines":[{"productId":"prod-1","sku":"SKU-1","quantity":2,"unitPrice":{"amount":"1500","currency":"JPY"}}],
     "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"千代田区","line1":"千代田 1-1"}}
    """

/** infra/local(コネクタの設定の kafka-connect ディレクトリの場所)。 */
private val INFRA: Path = KafkaConnectContainer.infraDirectory

/**
 * Outbox → Debezium(Kafka Connect)→ Kafka の発行を、ローカル基盤と同じ構成で確かめる(P06 の DoD。ADR-0007)。
 *
 * - Kafka Connect は compose と同じイメージと環境変数で立て(`KafkaConnectContainer`)、コネクタは
 *   infra/local/kafka-connect/order-outbox.json をそのまま登録する。
 * - Kafka は自動でトピックを作らない(compose と同じ)。出力先のトピックは Connect が作る(topic.creation.*)。
 * - Kafka の停止は `docker pause` で行う(ブローカーが応答しなくなる。ホスト側のポートが変わらないので、テストのクライアントが
 *   再開の後もつなげる)。compose の `stop` / `start` を伴う止まり方は `make verify PROFILE=order` で確かめる。
 */
class OrderOutboxCdcIT :
    FunSpec({
        val network = Network.newNetwork()
        val environment = AppEnvironment(network)
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withNetwork(network)
                .withListener("kafka:19092")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val connect =
            KafkaConnectContainer("kafka:19092", mapOf("DEBEZIUM_DB_PASSWORD" to environment.cdcPassword)).withNetwork(network)
        lateinit var server: OrderServer
        lateinit var db: String

        fun connectUrl(path: String) = "${connect.restUrl}$path"

        val http = JdkHttpClient.newHttpClient()

        fun connectRequest(
            method: String,
            path: String,
            body: String? = null,
        ): HttpResponse<String> =
            http.send(
                HttpRequest
                    .newBuilder(URI.create(connectUrl(path)))
                    .header("Content-Type", "application/json")
                    .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun place(key: String) = environment.gateway.post("https://localhost:${server.httpsPort}/v1/orders", environment.token(), key, BODY)

        fun orderIdOf(body: String): String = Regex(""""id":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: error("注文 ID がありません")

        fun slotConfirmedLsn(): String =
            environment.superuser(db) { c ->
                c.createStatement().use { s ->
                    s.executeQuery(SLOT_LSN).use { rs ->
                        check(rs.next()) { "スロット order_outbox がありません" }
                        rs.getString(1)
                    }
                }
            }

        fun lsnAtLeast(
            actual: String,
            target: String,
        ): Boolean =
            environment.superuser(db) { c ->
                c.prepareStatement("SELECT ?::pg_lsn >= ?::pg_lsn").use { s ->
                    s.setString(1, actual)
                    s.setString(2, target)
                    s.executeQuery().use { rs -> rs.next().let { rs.getBoolean(1) } }
                }
            }

        /** [expected] の注文 ID のイベントが全部そろうまで読む([timeout] まで)。同じ ID の重複(At-Least-Once)も返す。 */
        fun consumeOrders(
            expected: Set<String>,
            timeout: kotlin.time.Duration = 120.seconds,
        ): List<ConsumerRecord<String, ByteArray>> {
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "order-cdc-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val received = mutableListOf<ConsumerRecord<String, ByteArray>>()
            KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(TOPIC))
                val deadline = TimeSource.Monotonic.markNow() + timeout
                while (!received.map { it.key() }.toSet().containsAll(expected) && deadline.hasNotPassedNow()) {
                    received += consumer.poll(Duration.ofMillis(500)).filter { it.key() in expected }
                }
            }
            return received
        }

        beforeSpec {
            kafka.start()
            environment.start()
            connect.start()
            db = environment.newDatabase("order_service")
            OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
            server = OrderServer.start(environment.serveEnv(db)).ok().also { environment.awaitReady(it) }

            // infra/local と同じコネクタの設定を登録する(PUT は作成と更新のどちらにもなる)
            val config = Json.parseToJsonElement(INFRA.resolve("kafka-connect/order-outbox.json").readText()).jsonObject
            config["name"]!!.jsonPrimitive.content shouldBe CONNECTOR
            val put = connectRequest("PUT", "/connectors/$CONNECTOR/config", config["config"].toString())
            check(put.statusCode() in 200..201) { "コネクタを登録できません: ${put.statusCode()} ${put.body()}" }
            val deadline = TimeSource.Monotonic.markNow() + 90.seconds
            while (true) {
                val status = Json.parseToJsonElement(connectRequest("GET", "/connectors/$CONNECTOR/status").body()).jsonObject
                val running =
                    status["connector"]
                        ?.jsonObject
                        ?.get("state")
                        ?.jsonPrimitive
                        ?.content == "RUNNING" &&
                        status["tasks"]?.jsonArray?.let { tasks ->
                            tasks.isNotEmpty() && tasks.all { (it as JsonObject)["state"]?.jsonPrimitive?.content == "RUNNING" }
                        } == true
                if (running) break
                check(deadline.hasNotPassedNow()) { "コネクタが RUNNING になりません: $status" }
                Thread.sleep(1_000)
            }
        }

        afterSpec {
            server.stop()
            connect.stop()
            kafka.stop()
            environment.close()
            network.close()
        }

        test("注文を作ると、sales.order.created.v1 に、注文 ID のキー・CloudEvents のヘッダ・契約のスキーマのペイロードで届く。Outbox に行は残らない") {
            val created = place("key-basic")
            created.statusCode() shouldBe 201
            val orderId = orderIdOf(created.body())

            val record = consumeOrders(setOf(orderId)).firstOrNull() ?: fail("イベントが届きません($orderId)")

            record.key() shouldBe orderId
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata.type shouldBe "sales.order.created"
            metadata.source shouldBe "/sales/order-service"
            metadata.correlationId.value shouldBe created.header("X-Correlation-Id")
            // EventRouter が付ける id ヘッダは ce_id と同じ値(Outbox の id 列 = ce_id 列。ADR-0007)
            record
                .headers()
                .lastHeader("id")
                ?.value()
                ?.toString(Charsets.UTF_8) shouldBe metadata.id.toString()
            // 契約のヘッダ(StandardHeaders)と EventRouter の id だけ。Debezium の内部のヘッダ(__debezium.*)は付けない
            record.headers().map { it.key() }.toSet() shouldBe
                setOf("id", "ce_id", "ce_source", "ce_type", "ce_time", "ce_specversion", "traceparent", "correlationid")
            val event =
                HttpClient(CIO).use { client ->
                    val schemas =
                        WriterSchemas(
                            ApicurioRegistryClient(SchemaRegistryConfig(environment.registry.baseUrl, requestTimeout = 30.seconds), client),
                        )
                    AvroEventDeserializer(OrderCreatedV1.serializer(), schemas).deserialize(record.value()).ok()
                }
            event.order.id shouldBe orderId
            event.order.totalAmount.minorUnits shouldBe 3_000
            environment.count(db, "SELECT count(*) FROM outbox.outbox") shouldBe 0
        }

        test("Kafka を止めたまま注文を作っても、再開の後に欠けずに届く(DoD)。止めている間も注文は 201 で、Outbox に行は残らない") {
            kafka.dockerClient.pauseContainerCmd(kafka.containerId).exec()
            val orderIds =
                try {
                    (1..5)
                        .map { n ->
                            val created = place("key-paused-$n")
                            created.statusCode() shouldBe 201
                            orderIdOf(created.body())
                        }.also {
                            environment.count(db, "SELECT count(*) FROM outbox.outbox") shouldBe 0
                            // 止めている間は発行されないので、slot が WAL を保持している(欠けない理由)
                            environment.count(db, "SELECT count(*) FROM orders") shouldBe 6
                        }
                } finally {
                    kafka.dockerClient.unpauseContainerCmd(kafka.containerId).exec()
                }

            val received = consumeOrders(orderIds.toSet(), 180.seconds)
            received.map { it.key() }.toSet() shouldContainAll orderIds
            received.forEach { EventMetadata.fromHeaders(it.headers()).ok().type shouldBe "sales.order.created" }
        }

        test("注文がない間も、heartbeat でスロットが進む(ほかの DB の WAL が増えても、WAL を保持し続けない)") {
            // ほかの DB(postgres)だけで WAL を進める。order_service には変更がない
            val target =
                environment.superuser(environment.postgres.databaseName) { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE TABLE IF NOT EXISTS wal_filler (id bigserial PRIMARY KEY, note text)")
                        s.execute("INSERT INTO wal_filler (note) SELECT repeat('x', 100) FROM generate_series(1, 1000)")
                        s.executeQuery("SELECT pg_current_wal_lsn()::text").use { rs -> rs.next().let { rs.getString(1) } }
                    }
                }
            val before = slotConfirmedLsn()
            lsnAtLeast(before, target) shouldBe false

            // heartbeat.interval.ms は 10 秒(order-outbox.json)。2 回分より長く待つ
            val deadline = TimeSource.Monotonic.markNow() + 60.seconds
            while (!lsnAtLeast(slotConfirmedLsn(), target)) {
                check(deadline.hasNotPassedNow()) { "スロットが進みません(before=$before, now=${slotConfirmedLsn()}, target=$target)" }
                Thread.sleep(1_000)
            }
        }
    })
