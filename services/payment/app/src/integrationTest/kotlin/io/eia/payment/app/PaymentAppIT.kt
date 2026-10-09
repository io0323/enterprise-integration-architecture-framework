@file:Suppress("MagicNumber") // 件数・数量・待ち時間・ポート

package io.eia.payment.app

import io.eia.payment.adapters.inbound.AuthorizePaymentV1
import io.eia.payment.adapters.inbound.MoneyV1
import io.eia.payment.adapters.inbound.PaymentCommandHandlers
import io.eia.payment.adapters.inbound.VoidPaymentV1
import io.eia.payment.adapters.out.outbox.PaymentAuthorizedV1
import io.eia.payment.adapters.out.outbox.PaymentDeclineReasonV1
import io.eia.payment.adapters.out.outbox.PaymentDeclinedV1
import io.eia.payment.adapters.out.outbox.PaymentEventSchemas
import io.eia.payment.adapters.out.outbox.PaymentVoidOutcomeV1
import io.eia.payment.adapters.out.outbox.PaymentVoidedV1
import io.eia.payment.adapters.out.persistence.PaymentSchema
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.withSpan
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaConnectContainer
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
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

private const val CONNECTOR = "payment-outbox"
private const val OWNER_ROLE = "payment_service"
private const val DATABASE = "payment_service"

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })

/**
 * payment-service の全体を、ローカル基盤と同じ構成で確かめる(P07 ④b。ADR-0028・ADR-0029)。
 *
 * コマンド(Kafka)→ payment(EventConsumer・冪等消費・承認)→ Outbox → Debezium(infra/local/kafka-connect/payment-outbox.json をそのまま登録)
 * → 返事のトピック。ロールはローカル基盤と同じ(所有者・アプリ・Debezium。Debezium の DB への CONNECT はマイグレーションが付ける)。
 */
class PaymentAppIT :
    FunSpec({
        val network = Network.newNetwork()
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .withNetwork(network)
                .withNetworkAliases("postgres")
        val registry = ApicurioRegistryContainer()
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withNetwork(network)
                .withListener("kafka:19092")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val cdcPassword = randomHex()
        val connect = KafkaConnectContainer("kafka:19092", mapOf("DEBEZIUM_DB_PASSWORD" to cdcPassword)).withNetwork(network)
        val http = JdkHttpClient.newHttpClient()
        val ktor = HttpClient(CIO)
        val runtime = Observability.init(ObservabilityConfig.of("payment-it").ok(), TelemetrySinks(), installLogAppender = false)
        lateinit var server: PaymentServer
        lateinit var client: ApicurioRegistryClient
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var commandASerializer: AvroEventSerializer<AuthorizePaymentV1>
        lateinit var commandBSerializer: AvroEventSerializer<VoidPaymentV1>

        fun url(): String = postgres.jdbcUrl.replaceAfterLast('/', DATABASE)

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

        fun health(): Int =
            http
                .send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.healthPort}/health/ready")).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()

        /** [topic] を最初から読み、キーが [sagaId] のものを [count] 件そろうまで([timeout] まで)読む。 */
        fun <T> replies(
            topic: EventTopic,
            serializer: KSerializer<T>,
            sagaId: String,
            count: Int = 1,
            timeout: kotlin.time.Duration = 90.seconds,
        ): List<Pair<ConsumerRecord<String, ByteArray>, T>> {
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "payment-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val deserializer = AvroEventDeserializer(serializer, WriterSchemas(client))
            val received = mutableListOf<Pair<ConsumerRecord<String, ByteArray>, T>>()
            KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(topic.name))
                val deadline = TimeSource.Monotonic.markNow() + timeout
                while (received.size < count && deadline.hasNotPassedNow()) {
                    consumer.poll(Duration.ofMillis(500)).filter { it.key() == sagaId }.forEach { record ->
                        val value = kotlinx.coroutines.runBlocking { deserializer.deserialize(record.value()) }.ok()
                        received += record to value
                    }
                }
            }
            if (received.size < count) fail("$topic に $sagaId の返事が $count 件届きません(${received.size} 件)")
            return received
        }

        suspend fun authorize(
            sagaId: String,
            amount: Long,
        ) = EventProducer(producer, runtime, "/sales/order-service")
            .send(commandASerializer, sagaId, AuthorizePaymentV1(sagaId, "order-$sagaId", "cust-1", MoneyV1(amount, "JPY")))
            .ok()

        suspend fun void(sagaId: String) =
            EventProducer(
                producer,
                runtime,
                "/sales/order-service",
            ).send(commandBSerializer, sagaId, VoidPaymentV1(sagaId, "order-$sagaId")).ok()

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            connect.start()
            // ローカル基盤(infra/local/postgres/init)と同じロール。Debezium の CONNECT はマイグレーションが付ける(ここでは付けない)
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                        s.execute(
                            "CREATE ROLE ${PaymentSchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                        )
                        s.execute("CREATE ROLE ${PaymentSchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '$cdcPassword'")
                        s.execute("CREATE DATABASE $DATABASE OWNER $OWNER_ROLE")
                        s.execute("REVOKE ALL ON DATABASE $DATABASE FROM PUBLIC")
                        s.execute("GRANT CONNECT ON DATABASE $DATABASE TO ${PaymentSchema.APP_ROLE}")
                    }
                }
            PaymentCommands.run(listOf("migrate"), mapOf("PAYMENT_DB_URL" to url(), "PAYMENT_DB_PASSWORD" to ownerPassword)) shouldBe
                PaymentCommands.OK

            // 契約の登録(make schemas と同じ)。コマンドは order が送るので、ここではテストが送るために使う
            client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), ktor)
            val contracts =
                KafkaConnectContainer.infraDirectory.parent.parent
                    .resolve("contracts/avro/payment")
            val commandASubject =
                SchemaSubject(PaymentCommandHandlers.AUTHORIZE.name, contracts.resolve("AuthorizePayment.avsc").readText())
            val commandBSubject = SchemaSubject(PaymentCommandHandlers.VOID.name, contracts.resolve("VoidPayment.avsc").readText())
            (PaymentEventSchemas.subjects + commandASubject + commandBSubject).forEach { client.register(it).ok() }
            val ids = SchemaIdBook(listOf(commandASubject, commandBSubject), client).also { it.resolve().ok() }
            commandASerializer =
                AvroEventSerializer(PaymentCommandHandlers.AUTHORIZE, commandASubject, AuthorizePaymentV1.serializer(), ids)
            commandBSerializer = AvroEventSerializer(PaymentCommandHandlers.VOID, commandBSubject, VoidPaymentV1.serializer(), ids)

            // コマンドのトピック(本来は order の Outbox のコネクタが作る)と DLQ(infra/local/kafka/topics.conf)
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val topics = listOf(PaymentCommandHandlers.AUTHORIZE.name, PaymentCommandHandlers.VOID.name)
                admin
                    .createTopics(
                        topics.flatMap { listOf(NewTopic(it, 3, 1), NewTopic(DeadLetterPublisher.deadLetterTopic(it), 3, 1)) },
                    ).all()
                    .get()
            }
            producer = KafkaProducer(KafkaProducerSettings(kafka.bootstrapServers, "payment-it").toProperties())

            val config =
                Json
                    .parseToJsonElement(
                        KafkaConnectContainer.infraDirectory.resolve("kafka-connect/$CONNECTOR.json").readText(),
                    ).jsonObject
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
                check(deadline.hasNotPassedNow()) { "コネクタが RUNNING になりません: $status\n${connect.diagnostics(CONNECTOR)}" }
                Thread.sleep(1_000)
            }

            server =
                PaymentServer
                    .start(
                        mapOf(
                            "PAYMENT_DB_URL" to url(),
                            "PAYMENT_APP_DB_PASSWORD" to appPassword,
                            "PAYMENT_KAFKA_BOOTSTRAP" to kafka.bootstrapServers,
                            "PAYMENT_SCHEMA_REGISTRY_URL" to registry.baseUrl,
                            "PAYMENT_HEALTH_PORT" to "0",
                        ),
                    ).ok()
            val ready = TimeSource.Monotonic.markNow() + 60.seconds
            while (health() != 200) {
                check(ready.hasNotPassedNow()) { "payment-service が ready になりません" }
                Thread.sleep(500)
            }
        }

        afterSpec {
            server.stop()
            producer.close()
            ktor.close()
            runtime.close()
            connect.stop()
            kafka.stop()
            registry.stop()
            postgres.stop()
            network.close()
        }

        test("承認の指示に、Saga ID のキー・同じトレースの返事(payment.payment.authorized.v1)が Outbox と Debezium で届く") {
            val sagaId = "saga-${Uuid.random()}"
            val sent = runtime.withSpan("test order saga") { authorize(sagaId, 5_000) }

            val (record, value) = replies(PaymentEventSchemas.PAYMENT_AUTHORIZED, PaymentAuthorizedV1.serializer(), sagaId).single()
            value.sagaId shouldBe sagaId
            value.authorizationId.isNotBlank() shouldBe true
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata.source shouldBe "/payment/payment-service"
            metadata.traceParent.traceId shouldBe sent.metadata.traceParent.traceId
            metadata.correlationId shouldBe sent.metadata.correlationId
        }

        test("上限(既定 1,000,000 円)を超える金額は、payment.payment.declined.v1(LIMIT_EXCEEDED)") {
            val sagaId = "saga-${Uuid.random()}"
            authorize(sagaId, 1_000_001)
            replies(PaymentEventSchemas.PAYMENT_DECLINED, PaymentDeclinedV1.serializer(), sagaId).single().second shouldBe
                PaymentDeclinedV1(sagaId, "order-$sagaId", PaymentDeclineReasonV1.LIMIT_EXCEEDED)
        }

        test("取消が先に届くと NOT_AUTHORIZED を返し、後から届いた承認の指示は ALREADY_VOIDED で拒否する(ADR-0029 §5)") {
            val sagaId = "saga-${Uuid.random()}"
            void(sagaId)
            replies(PaymentEventSchemas.PAYMENT_VOIDED, PaymentVoidedV1.serializer(), sagaId).single().second.outcome shouldBe
                PaymentVoidOutcomeV1.NOT_AUTHORIZED
            authorize(sagaId, 100)
            replies(PaymentEventSchemas.PAYMENT_DECLINED, PaymentDeclinedV1.serializer(), sagaId).single().second.reason shouldBe
                PaymentDeclineReasonV1.ALREADY_VOIDED
        }

        test("Poison Message(Avro として読めないコマンド)は DLQ に隔離され、後続のコマンドは処理される") {
            val sagaId = "saga-${Uuid.random()}"
            val poison =
                ProducerRecord<ByteArray, ByteArray>(PaymentCommandHandlers.AUTHORIZE.name, sagaId.toByteArray(), byteArrayOf(1, 2, 3))
            EventMetadata(
                Uuid.random(),
                "/sales/order-service",
                PaymentCommandHandlers.AUTHORIZE.ceType,
                kotlin.time.Clock.System
                    .now(),
                io.eia.shared.resilience.trace.TraceParent
                    .parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                    .ok(),
                io.eia.shared.kernel.CorrelationId
                    .generate(),
            ).toHeaders().forEach { poison.headers().add(it) }
            producer.send(poison).get()
            authorize(sagaId, 100)
            replies(PaymentEventSchemas.PAYMENT_AUTHORIZED, PaymentAuthorizedV1.serializer(), sagaId).size shouldBe 1
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "payment-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(DeadLetterPublisher.deadLetterTopic(PaymentCommandHandlers.AUTHORIZE.name)))
                val deadline = TimeSource.Monotonic.markNow() + 30.seconds
                var reason: String? = null
                while (reason == null && deadline.hasNotPassedNow()) {
                    reason =
                        consumer
                            .poll(Duration.ofMillis(500))
                            .firstOrNull { String(it.key()) == sagaId }
                            ?.headers()
                            ?.lastHeader("eiaf.dlq.reason")
                            ?.value()
                            ?.let(::String)
                }
                reason shouldBe "UNDECODABLE"
            }
            health() shouldBe 200
        }
    })
