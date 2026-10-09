@file:Suppress("MagicNumber") // 件数・待ち時間

package io.eia.order.app

import io.eia.order.adapters.inbound.kafka.PaymentAuthorizedV1
import io.eia.order.adapters.inbound.kafka.PaymentDeclineReasonV1
import io.eia.order.adapters.inbound.kafka.PaymentDeclinedV1
import io.eia.order.adapters.inbound.kafka.SagaReplyHandlers
import io.eia.order.adapters.inbound.kafka.ShipmentShippedV1
import io.eia.order.adapters.inbound.kafka.StockReleaseOutcomeV1
import io.eia.order.adapters.inbound.kafka.StockReleasedV1
import io.eia.order.adapters.inbound.kafka.StockReservedV1
import io.eia.order.adapters.out.outbox.OrderCancelledV1
import io.eia.order.adapters.out.outbox.OrderEventSchemas
import io.eia.order.adapters.out.saga.SagaSchemas
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaConnectContainer
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val CONNECTOR = "order-outbox"
private const val STEP_TIMEOUT_SECONDS = 4

private fun body(amount: Long = 1500) =
    """
    {"customerId":"cust-1",
     "lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"$amount","currency":"JPY"}}],
     "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"千代田区","line1":"千代田 1-1"}}
    """

/**
 * 注文 Saga の Orchestrator(order-service)を、ローカル基盤と同じ構成で確かめる(P07 ⑤。ADR-0029)。
 *
 * order-service の API で注文を作り、コマンドが Outbox → Debezium(infra/local の order-outbox のコネクタ)でコマンドのトピックに届くことを確かめる。
 * 参加者(inventory / payment / shipping)の代わりに、テストが返信のトピックに返信を送る(参加者の全体は E2E。P07 ⑥)。
 * 段の期限は短くする(4 秒。DB の時計で判定する)。
 */
class OrderSagaIT :
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
        val connect = KafkaConnectContainer("kafka:19092", mapOf("DEBEZIUM_DB_PASSWORD" to environment.cdcPassword)).withNetwork(network)
        val http = JdkHttpClient.newHttpClient()
        val ktor = HttpClient(CIO)
        val runtime = Observability.init(ObservabilityConfig.of("order-saga-it").ok(), TelemetrySinks(), installLogAppender = false)
        lateinit var server: OrderServer
        lateinit var client: ApicurioRegistryClient
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var replyIds: SchemaIdBook

        fun connectRequest(
            method: String,
            path: String,
            body: String? = null,
        ): HttpResponse<String> =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("${connect.restUrl}$path"))
                    .header("Content-Type", "application/json")
                    .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun place(amount: Long = 1500): String {
            val created =
                environment.gateway.post(
                    "https://localhost:${server.httpsPort}/v1/orders",
                    environment.token(),
                    "key-${Uuid.random()}",
                    body(amount),
                )
            created.statusCode() shouldBe 201
            return Regex(""""id":"([^"]+)"""").find(created.body())?.groupValues?.get(1) ?: error("注文 ID がありません")
        }

        fun status(orderId: String): String {
            val got = environment.gateway.get("https://localhost:${server.httpsPort}/v1/orders/$orderId", environment.token())
            return Regex(""""status":"([A-Z]+)"""").find(got.body())?.groupValues?.get(1) ?: error("状態がありません: ${got.statusCode()}")
        }

        fun awaitStatus(
            orderId: String,
            expected: String,
        ) {
            val deadline = TimeSource.Monotonic.markNow() + 60.seconds
            while (status(orderId) != expected) {
                check(deadline.hasNotPassedNow()) { "注文 $orderId が $expected になりません(${status(orderId)})" }
                Thread.sleep(500)
            }
        }

        /** [topic] を最初から読み、[match] に合う値を [count] 件読む(キーは Saga ID)。 */
        fun <T> read(
            topic: EventTopic,
            serializer: KSerializer<T>,
            count: Int = 1,
            match: (T) -> Boolean,
        ): List<Pair<String, T>> {
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "order-saga-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val deserializer = AvroEventDeserializer(serializer, WriterSchemas(client))
            val found = mutableListOf<Pair<String, T>>()
            KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(topic.name))
                val deadline = TimeSource.Monotonic.markNow() + 60.seconds
                while (found.size < count && deadline.hasNotPassedNow()) {
                    found +=
                        consumer
                            .poll(Duration.ofMillis(500))
                            .map { record -> record.key() to runBlocking { deserializer.deserialize(record.value()) }.ok() }
                            .filter { match(it.second) }
                }
            }
            if (found.size < count) fail("$topic に $count 件届きません(${found.size} 件)")
            return found
        }

        fun reserveCommand(orderId: String): String =
            read(
                SagaSchemas.RESERVE_STOCK,
                io.eia.order.adapters.out.saga.ReserveStockV1
                    .serializer(),
            ) { it.orderId == orderId }.single().first

        fun <T> reply(
            topic: EventTopic,
            serializer: KSerializer<T>,
            schema: String,
            sagaId: String,
            value: T,
        ) {
            val subject = SchemaSubject(topic.name, schema)
            runBlocking {
                EventProducer(producer, runtime, "/test/participant")
                    .send(AvroEventSerializer(topic, subject, serializer, replyIds), sagaId, value)
                    .ok()
            }
        }

        val contracts =
            KafkaConnectContainer.infraDirectory.parent.parent
                .resolve("contracts/avro")

        fun schema(path: String) = contracts.resolve(path).readText()

        beforeSpec {
            kafka.start()
            environment.start()
            connect.start()
            client = ApicurioRegistryClient(SchemaRegistryConfig(environment.registry.baseUrl, requestTimeout = 30.seconds), ktor)
            // 返信の契約(参加者が登録するもの。make schemas と同じ)
            val replySubjects =
                listOf(
                    SchemaSubject(SagaReplyHandlers.STOCK_RESERVED.name, schema("inventory/StockReserved.avsc")),
                    SchemaSubject(SagaReplyHandlers.STOCK_RELEASED.name, schema("inventory/StockReleased.avsc")),
                    SchemaSubject(SagaReplyHandlers.PAYMENT_AUTHORIZED.name, schema("payment/PaymentAuthorized.avsc")),
                    SchemaSubject(SagaReplyHandlers.PAYMENT_DECLINED.name, schema("payment/PaymentDeclined.avsc")),
                    SchemaSubject(SagaReplyHandlers.SHIPMENT_SHIPPED.name, schema("shipping/ShipmentShipped.avsc")),
                )
            replySubjects.forEach { client.register(it).ok() }
            replyIds = SchemaIdBook(replySubjects, client).also { it.resolve().ok() }
            producer = KafkaProducer(KafkaProducerSettings(kafka.bootstrapServers, "order-saga-it").toProperties())
            // 返信のトピック(本来は参加者の Outbox のコネクタが作る)
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val replies =
                    listOf(
                        SagaReplyHandlers.STOCK_RESERVED,
                        SagaReplyHandlers.STOCK_RESERVATION_REJECTED,
                        SagaReplyHandlers.STOCK_RELEASED,
                        SagaReplyHandlers.PAYMENT_AUTHORIZED,
                        SagaReplyHandlers.PAYMENT_DECLINED,
                        SagaReplyHandlers.PAYMENT_VOIDED,
                        SagaReplyHandlers.SHIPMENT_SHIPPED,
                        SagaReplyHandlers.SHIPMENT_REJECTED,
                        SagaReplyHandlers.SHIPMENT_CANCELLED,
                    )
                admin.createTopics(replies.map { NewTopic(it.name, 3, 1) }).all().get()
            }

            val db = environment.newDatabase("order_service")
            OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
            server =
                OrderServer
                    .start(
                        environment.serveEnv(
                            db,
                            mapOf(
                                "ORDER_KAFKA_BOOTSTRAP" to kafka.bootstrapServers,
                                "ORDER_SAGA_STEP_TIMEOUT" to "${STEP_TIMEOUT_SECONDS}s",
                                "ORDER_SAGA_COMPENSATION_INTERVAL" to "2s",
                                "ORDER_SAGA_TIMEOUT_SCAN_INTERVAL" to "500ms",
                            ),
                        ),
                    ).ok()
                    .also { environment.awaitReady(it) }

            val config =
                Json
                    .parseToJsonElement(
                        KafkaConnectContainer.infraDirectory.resolve("kafka-connect/$CONNECTOR.json").readText(),
                    ).jsonObject
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
                check(deadline.hasNotPassedNow()) { "コネクタが RUNNING になりません: $status\n${connect.diagnostics(CONNECTOR)}" }
                Thread.sleep(1_000)
            }
        }

        afterSpec {
            server.stop()
            producer.close()
            ktor.close()
            runtime.close()
            connect.stop()
            kafka.stop()
            environment.close()
            network.close()
        }

        test("正常: 引当 → 承認 → 出荷の返信で、コマンドが順に Outbox と Debezium で届き、注文は CONFIRMED → SHIPPED になる") {
            val orderId = place()
            val sagaId = reserveCommand(orderId)
            status(orderId) shouldBe "PLACED"

            reply(
                SagaReplyHandlers.STOCK_RESERVED,
                StockReservedV1.serializer(),
                schema("inventory/StockReserved.avsc"),
                sagaId,
                StockReservedV1(sagaId, orderId),
            )
            val authorize =
                read(
                    SagaSchemas.AUTHORIZE_PAYMENT,
                    io.eia.order.adapters.out.saga.AuthorizePaymentV1
                        .serializer(),
                ) {
                    it.sagaId ==
                        sagaId
                }
            authorize
                .single()
                .second.amount.minorUnits shouldBe 1500

            reply(
                SagaReplyHandlers.PAYMENT_AUTHORIZED,
                PaymentAuthorizedV1.serializer(),
                schema("payment/PaymentAuthorized.avsc"),
                sagaId,
                PaymentAuthorizedV1(sagaId, orderId, "auth-1"),
            )
            awaitStatus(orderId, "CONFIRMED")
            val arrange =
                read(
                    SagaSchemas.ARRANGE_SHIPMENT,
                    io.eia.order.adapters.out.saga.ArrangeShipmentV1
                        .serializer(),
                ) {
                    it.sagaId ==
                        sagaId
                }
            arrange
                .single()
                .second.shippingAddress.countryCode shouldBe "JP"

            reply(
                SagaReplyHandlers.SHIPMENT_SHIPPED,
                ShipmentShippedV1.serializer(),
                schema("shipping/ShipmentShipped.avsc"),
                sagaId,
                ShipmentShippedV1(
                    sagaId,
                    orderId,
                    "ship-1",
                    kotlin.time.Clock.System
                        .now(),
                ),
            )
            awaitStatus(orderId, "SHIPPED")
        }

        test("決済の失敗: 在庫の解放のコマンドを送り、解放の返信で注文は CANCELLED、sales.order.cancelled.v1(PAYMENT_DECLINED)") {
            val orderId = place()
            val sagaId = reserveCommand(orderId)
            reply(
                SagaReplyHandlers.STOCK_RESERVED,
                StockReservedV1.serializer(),
                schema("inventory/StockReserved.avsc"),
                sagaId,
                StockReservedV1(sagaId, orderId),
            )
            read(
                SagaSchemas.AUTHORIZE_PAYMENT,
                io.eia.order.adapters.out.saga.AuthorizePaymentV1
                    .serializer(),
            ) { it.sagaId == sagaId }
            reply(
                SagaReplyHandlers.PAYMENT_DECLINED,
                PaymentDeclinedV1.serializer(),
                schema("payment/PaymentDeclined.avsc"),
                sagaId,
                PaymentDeclinedV1(sagaId, orderId, PaymentDeclineReasonV1.LIMIT_EXCEEDED),
            )
            read(
                SagaSchemas.RELEASE_STOCK,
                io.eia.order.adapters.out.saga.ReleaseStockV1
                    .serializer(),
            ) { it.sagaId == sagaId }
            status(orderId) shouldBe "PLACED"

            reply(
                SagaReplyHandlers.STOCK_RELEASED,
                StockReleasedV1.serializer(),
                schema("inventory/StockReleased.avsc"),
                sagaId,
                StockReleasedV1(sagaId, orderId, StockReleaseOutcomeV1.RELEASED),
            )
            awaitStatus(orderId, "CANCELLED")
            read(OrderEventSchemas.ORDER_CANCELLED, OrderCancelledV1.serializer()) { it.orderId == orderId }.single().second.reason shouldBe
                "PAYMENT_DECLINED"
        }

        test("タイムアウト: 引当の返信がなければ、期限(DB の時計)の後に解放を送り、返信がなければ送り直す。解放の返信で CANCELLED(TIMED_OUT)") {
            val orderId = place()
            val sagaId = reserveCommand(orderId)

            // 期限(4 秒)の後に補償(在庫の解放)が届き、さらに送り直しの間隔(2 秒)ごとに同じ解放が届く
            val releases =
                read(
                    SagaSchemas.RELEASE_STOCK,
                    io.eia.order.adapters.out.saga.ReleaseStockV1
                        .serializer(),
                    count = 2,
                ) {
                    it.sagaId ==
                        sagaId
                }
            releases.map { it.first }.toSet() shouldBe setOf(sagaId)
            status(orderId) shouldBe "PLACED"

            reply(
                SagaReplyHandlers.STOCK_RELEASED,
                StockReleasedV1.serializer(),
                schema("inventory/StockReleased.avsc"),
                sagaId,
                StockReleasedV1(sagaId, orderId, StockReleaseOutcomeV1.NOT_RESERVED),
            )
            awaitStatus(orderId, "CANCELLED")
            read(OrderEventSchemas.ORDER_CANCELLED, OrderCancelledV1.serializer()) { it.orderId == orderId }.single().second.reason shouldBe
                "TIMED_OUT"
        }
    })
