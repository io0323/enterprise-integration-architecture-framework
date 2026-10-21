@file:Suppress("MagicNumber") // 待ち時間・件数・乱数のバイト数

package io.eia.integrationflow.app

import com.sun.net.httpserver.HttpServer
import io.eia.integrationflow.adapters.inbound.kafka.WebhookIngestHandler
import io.eia.integrationflow.adapters.inbound.kafka.WebhookReceivedV1
import io.eia.integrationflow.adapters.out.outbox.CustomerChangedV1
import io.eia.integrationflow.adapters.out.outbox.FlowEventSchemas
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.DeadLetterPublisher
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
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val DATABASE = "integration_flow"
private const val OWNER = "integration_flow"
private const val APP = "integration_flow_app"
private val INGEST = EventTopic.of("integration.crm-webhook.received.v1")
private val PAYLOAD = Regex("""payload\[bytea]:'\\x([0-9a-f]+)'""")

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

private fun crmCustomer(
    id: String,
    version: Int,
    email: String? = "${id.uppercase()}@Example.COM",
): String =
    """{"id":"$id","display_name":" Customer $id ","created_at":"2026-10-21T00:00:00Z","updated_at":"2026-10-21T0$version:00:00Z",""" +
        """"version":$version,"loyalty_tier":"gold"${email?.let { ""","email":"$it"""" }.orEmpty()}}"""

/**
 * integration-flow の全体(ADR-0035・ADR-0036): 取り込みのトピック(Kafka)→ Flow → Canonical のイベントの Outbox(PostgreSQL の WAL)と、
 * CRM の API の定期の同期。発行(Debezium → Kafka)は P10 ⑥・⑦ で確かめる。
 */
class IntegrationFlowAppIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
        val registry = ApicurioRegistryContainer()
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val http = JdkHttpClient.newHttpClient()
        val ktor = HttpClient(CIO)
        val runtime = Observability.init(ObservabilityConfig.of("integration-flow-it").ok(), TelemetrySinks(), installLogAppender = false)
        val crmBody = AtomicReference("""{"data":[],"next_cursor":null}""")
        val crm =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/customers") { exchange ->
                    val (status, body) =
                        if (exchange.requestHeaders.getFirst("Authorization") == "Bearer crm-token") 200 to crmBody.get() else 401 to "{}"
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        val changes = mutableListOf<String>()
        lateinit var server: IntegrationFlowServer
        lateinit var client: ApicurioRegistryClient
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var serializer: AvroEventSerializer<WebhookReceivedV1>

        fun superuser(
            database: String = DATABASE,
            block: (Connection) -> Unit,
        ) = PGSimpleDataSource()
            .apply {
                setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
                user = postgres.username
                password = postgres.password
            }.connection
            .use(block)

        fun health(): Int =
            http
                .send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.healthPort}/health/ready")).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()

        /** WAL の Outbox への INSERT を、[predicate] を満たすまで([timeout] まで)読み足す。 */
        fun awaitOutbox(
            timeout: kotlin.time.Duration = 60.seconds,
            predicate: (List<String>) -> Boolean,
        ): List<String> {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (true) {
                superuser { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT data FROM pg_logical_slot_get_changes('flow_it', NULL, NULL)").use { rs ->
                            while (rs.next()) changes += rs.getString(1)
                        }
                    }
                }
                val inserts = changes.filter { it.startsWith("table outbox.outbox: INSERT") }
                if (predicate(inserts)) return inserts
                if (deadline.hasPassedNow()) fail("Outbox の INSERT がそろいません(${inserts.size} 件)")
                Thread.sleep(500)
            }
        }

        fun decode(insert: String): CustomerChangedV1 {
            val payload = HexFormat.of().parseHex(PAYLOAD.find(insert)?.groupValues?.get(1) ?: fail("payload がありません"))
            return runBlocking { AvroEventDeserializer(CustomerChangedV1.serializer(), WriterSchemas(client)).deserialize(payload) }.ok()
        }

        suspend fun webhook(
            webhookId: String,
            body: String,
        ) {
            val bytes = body.toByteArray()
            val event =
                WebhookReceivedV1(
                    webhookId,
                    "crm",
                    Instant.parse("2026-10-21T00:00:00Z"),
                    Instant.parse("2026-10-21T00:00:01Z"),
                    "application/json",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    bytes,
                )
            EventProducer(producer, runtime, "/integration/webhook-receiver").send(serializer, webhookId, event).ok()
        }

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            superuser(postgres.databaseName) { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE $OWNER LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE $APP LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                    s.execute("CREATE ROLE debezium LOGIN REPLICATION PASSWORD '${randomHex()}'")
                    s.execute("CREATE DATABASE $DATABASE OWNER $OWNER")
                    s.execute("REVOKE ALL ON DATABASE $DATABASE FROM PUBLIC")
                    s.execute("GRANT CONNECT ON DATABASE $DATABASE TO $APP")
                }
            }
            val url = postgres.jdbcUrl.replaceAfterLast('/', DATABASE)
            IntegrationFlowCommands.run(
                listOf("migrate"),
                mapOf(
                    "INTEGRATION_FLOW_DB_URL" to url,
                    "INTEGRATION_FLOW_DB_PASSWORD" to ownerPassword,
                ),
            ) shouldBe
                IntegrationFlowCommands.OK
            superuser { c ->
                c.createStatement().use { it.execute("SELECT pg_create_logical_replication_slot('flow_it', 'test_decoding')") }
            }

            // 契約の登録(make schemas と同じ)。WebhookReceived は webhook-receiver が書くので、ここではテストが送るために使う
            client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), ktor)
            val received =
                SchemaSubject(
                    INGEST.name,
                    checkNotNull(FlowEventSchemas::class.java.getResource("/contracts/avro/integration/WebhookReceived.avsc")).readText(),
                )
            (FlowEventSchemas.subjects + received).forEach { client.register(it).ok() }
            serializer =
                AvroEventSerializer(
                    INGEST,
                    received,
                    WebhookReceivedV1.serializer(),
                    SchemaIdBook(listOf(received), client).also { it.resolve().ok() },
                )

            // 取り込みのトピックと DLQ(本来は infra/local/kafka/topics.conf が作る)
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin
                    .createTopics(
                        listOf(NewTopic(INGEST.name, 3, 1), NewTopic(DeadLetterPublisher.deadLetterTopic(INGEST.name), 3, 1)),
                    ).all()
                    .get()
            }
            producer = KafkaProducer(KafkaProducerSettings(kafka.bootstrapServers, "integration-flow-it").toProperties())

            server =
                IntegrationFlowServer
                    .start(
                        mapOf(
                            "INTEGRATION_FLOW_DB_URL" to url,
                            "INTEGRATION_FLOW_APP_DB_PASSWORD" to appPassword,
                            IntegrationFlowConfig.KAFKA_BOOTSTRAP to kafka.bootstrapServers,
                            IntegrationFlowConfig.SCHEMA_REGISTRY_URL to registry.baseUrl,
                            "INTEGRATION_FLOW_HEALTH_PORT" to "0",
                            IntegrationFlowConfig.CRM_API_URL to "http://127.0.0.1:${crm.address.port}",
                            IntegrationFlowConfig.CRM_API_TOKEN.value to "crm-token",
                            "INTEGRATION_FLOW_POLL_INTERVAL" to "1s",
                            "EIA_LOG_FORMAT" to "console",
                        ),
                    ).ok()
            val deadline = TimeSource.Monotonic.markNow() + 60.seconds
            while (health() != 200) {
                check(deadline.hasNotPassedNow()) { "serve が ready になりませんでした" }
                Thread.sleep(200)
            }
        }
        afterSpec {
            server.stop()
            producer.close()
            ktor.close()
            crm.stop(0)
            kafka.stop()
            registry.stop()
            postgres.stop()
            runtime.close()
        }

        test("Webhook の顧客を Canonical の Customer にして、Outbox に書く(キーは顧客 ID。元の SaaS と版を運ぶ)") {
            webhook("evt_1", """{"type":"customer.updated","timestamp":"2026-10-21T03:00:00Z","data":${crmCustomer("cus_1", 3)}}""")
            val insert =
                awaitOutbox { inserts -> inserts.any { "aggregate_id[text]:'cus_1'" in it } }.single {
                    "aggregate_id[text]:'cus_1'" in
                        it
                }
            insert.contains("topic[text]:'sales.customer.changed.v1'") shouldBe true
            insert.contains("aggregate_type[text]:'customer'") shouldBe true
            insert.contains("ce_source[text]:'/integration/integration-flow'") shouldBe true
            val event = decode(insert)
            event.customer.id shouldBe "cus_1"
            event.customer.name shouldBe "Customer cus_1"
            event.customer.email shouldBe "cus_1@example.com"
            event.sourceSystem shouldBe "crm"
            event.sourceVersion shouldBe 3
        }

        test("条件 3: Webhook で新しい版(3)を受けた後の定期の同期は、古い版(2)を出さず、新しい顧客だけを出す") {
            crmBody.set("""{"data":[${crmCustomer("cus_1", 2)},${crmCustomer("cus_2", 1)}],"next_cursor":null}""")
            val inserts = awaitOutbox { all -> all.any { "aggregate_id[text]:'cus_2'" in it } }
            // 同期は 1 秒ごとに繰り返すが、版で判定するので、どちらの顧客も出し直さない
            Thread.sleep(3_000)
            val after = awaitOutbox { true }
            after.count { "aggregate_id[text]:'cus_1'" in it } shouldBe 1
            after.count { "aggregate_id[text]:'cus_2'" in it } shouldBe 1
            decode(inserts.single { "aggregate_id[text]:'cus_2'" in it }).sourceVersion shouldBe 1
            val versions = mutableListOf<String>()
            superuser { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT record_key || ':' || version FROM flow_version ORDER BY record_key").use { rs ->
                        while (rs.next()) {
                            versions +=
                                rs.getString(1)
                        }
                    }
                }
            }
            versions shouldBe listOf("cus_1:3", "cus_2:1")
        }

        test("Canonical の検証を通らない Webhook(メールがない)は DLQ に隔離し、後続の Webhook は処理する") {
            webhook("evt_bad", """{"type":"customer.updated","data":${crmCustomer("cus_3", 1, email = null)}}""")
            webhook("evt_ok", """{"type":"customer.created","data":${crmCustomer("cus_4", 1)}}""")
            awaitOutbox { inserts ->
                inserts.any { "aggregate_id[text]:'cus_4'" in it }
            }.none { "aggregate_id[text]:'cus_3'" in it } shouldBe
                true

            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "integration-flow-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(DeadLetterPublisher.deadLetterTopic(INGEST.name)))
                val deadline = TimeSource.Monotonic.markNow() + 30.seconds
                var reason: String? = null
                while (reason == null && deadline.hasNotPassedNow()) {
                    reason =
                        consumer
                            .poll(Duration.ofMillis(500))
                            .firstOrNull { String(it.key()) == "evt_bad" }
                            ?.headers()
                            ?.lastHeader("eiaf.dlq.reason")
                            ?.value()
                            ?.let(::String)
                }
                reason shouldBe "VALIDATION_FAILED"
            }
            WebhookIngestHandler.GROUP_ID shouldBe "integration-flow.webhook-ingest"
        }
    })
