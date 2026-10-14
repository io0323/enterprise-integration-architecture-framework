@file:Suppress("MagicNumber") // テストデータの金額・件数・待ち時間

package io.eia.batchetl.app

import io.eia.batchetl.domain.BusinessDay
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
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * batch-etl のコマンド(migrate・run)と常駐(serve)を、Kafka・Apicurio Registry・PostgreSQL で確かめる。
 * ロールはローカル基盤(infra/local/postgres/init)と同じ(所有者 `batch_etl` とアプリ用の `batch_etl_app`)。
 */
class BatchEtlAppIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val registry = ApicurioRegistryContainer()
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val ktor = HttpClient(CIO)
        val runtime =
            Observability.init(
                ObservabilityConfig.of("batch-etl-app-it").getOrNull()!!,
                TelemetrySinks(),
                installLogAppender = false,
            )
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var created: AvroEventSerializer<CreatedV1>

        fun appEnv(): Map<String, String> =
            mapOf(
                BatchEtlConfig.DB_URL to postgres.jdbcUrl.replaceAfterLast('/', "batch_etl"),
                BatchEtlConfig.APP_PASSWORD.value to appPassword,
                BatchEtlConfig.KAFKA_BOOTSTRAP to kafka.bootstrapServers,
                BatchEtlConfig.SCHEMA_REGISTRY_URL to registry.baseUrl,
                BatchEtlConfig.HEALTH_PORT to "0",
                BatchEtlConfig.POLL_INTERVAL to "1s",
                "EIA_LOG_FORMAT" to "console",
            )

        fun query(sql: String): String? {
            val dataSource =
                PGSimpleDataSource().apply {
                    setURL(postgres.jdbcUrl.replaceAfterLast('/', "batch_etl"))
                    user = postgres.username
                    password = postgres.password
                }
            return dataSource.connection.use { c ->
                val rs = c.createStatement().executeQuery(sql)
                if (rs.next()) rs.getString(1) else null
            }
        }

        suspend fun placed(
            id: String,
            orderedAt: String,
        ) {
            val money = MoneyV1(1500, "JPY")
            val order =
                CreatedV1(
                    OrderV1(
                        id,
                        "c-$id",
                        StatusV1.PLACED,
                        Instant.parse(orderedAt),
                        listOf(LineV1(1, "p-1", "SKU-1", 1, money, money)),
                        money,
                        AddressV1("JP", "530-0001", "大阪市北区", "梅田1-1", "大阪府", null),
                    ),
                )
            EventProducer(producer, runtime, "/sales/order-service").send(created, id, order).getOrNull() ?: fail("送れません")
        }

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE ROLE batch_etl LOGIN PASSWORD '$ownerPassword'")
                        s.execute("CREATE ROLE batch_etl_app LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                        s.execute("CREATE DATABASE batch_etl OWNER batch_etl")
                        s.execute("REVOKE ALL ON DATABASE batch_etl FROM PUBLIC")
                        s.execute("GRANT CONNECT ON DATABASE batch_etl TO batch_etl_app")
                    }
                }
            val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), ktor)
            val contracts =
                Path
                    .of(System.getProperty(InfraImages.FILE_PROPERTY))
                    .parent.parent.parent
                    .resolve("contracts/avro/sales")
            val subjects =
                listOf(
                    SchemaSubject("sales.order.created.v1", contracts.resolve("OrderCreated.avsc").readText()),
                    SchemaSubject("sales.order.cancelled.v1", contracts.resolve("OrderCancelled.avsc").readText()),
                    SchemaSubject("sales.order.status-changed.v1", contracts.resolve("OrderStatusChanged.avsc").readText()),
                )
            subjects.forEach { subject -> (client.register(subject) as? Result.Ok) ?: fail("登録できません: ${subject.topic}") }
            val ids = SchemaIdBook(subjects.take(1), client).also { (it.resolve() as? Result.Ok) ?: fail("ID を解決できません") }
            created = AvroEventSerializer(EventTopic.of("sales.order.created.v1"), subjects[0], CreatedV1.serializer(), ids)
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val topics = subjects.map { it.topic }
                admin.createTopics((topics + topics.map(DeadLetterPublisher::deadLetterTopic)).map { NewTopic(it, 2, 1) }).all().get()
            }
            producer = KafkaProducer(KafkaProducerSettings(kafka.bootstrapServers, "batch-etl-app-it").toProperties())
        }

        afterSpec {
            producer.close()
            ktor.close()
            kafka.stop()
            registry.stop()
            postgres.stop()
        }

        test("migrate は所有者で、serve・run はアプリのロールだけで動く。使い方の誤りは 2") {
            BatchEtlCommands.run(
                listOf("migrate"),
                mapOf(
                    BatchEtlConfig.DB_URL to postgres.jdbcUrl.replaceAfterLast('/', "batch_etl"),
                    BatchEtlConfig.OWNER_PASSWORD.value to ownerPassword,
                ),
            ) shouldBe BatchEtlCommands.OK
            BatchEtlCommands.run(listOf("migrate"), mapOf(BatchEtlConfig.DB_URL to "jdbc:postgresql://x/y")) shouldBe BatchEtlCommands.USAGE
            BatchEtlCommands.run(listOf("serve"), appEnv() + (BatchEtlConfig.OWNER_PASSWORD.value to ownerPassword)) shouldBe
                BatchEtlCommands.USAGE
            BatchEtlCommands.run(listOf("run", "2026-13-01"), appEnv()) shouldBe BatchEtlCommands.USAGE
            BatchEtlCommands.run(listOf("run", "2026-10-13", "--force"), appEnv()) shouldBe BatchEtlCommands.USAGE
            BatchEtlCommands.run(listOf("run", "2026-10-13"), appEnv() - BatchEtlConfig.KAFKA_BOOTSTRAP) shouldBe BatchEtlCommands.USAGE
            BatchEtlCommands.run(listOf("unknown"), appEnv()) shouldBe BatchEtlCommands.USAGE
        }

        test("run: 1 日分を実行して日次のパーティションを作る。成功した日はそのまま、--restart なら作り直す") {
            placed("a-1", "2026-10-13T01:00:00Z")
            placed("a-2", "2026-10-13T02:00:00Z")

            BatchEtlCommands.run(listOf("run", "2026-10-13"), appEnv()) shouldBe BatchEtlCommands.OK

            query("SELECT count(*) FROM mart.order_daily WHERE business_date = '2026-10-13'") shouldBe "2"
            query("SELECT ship_region FROM mart.order_daily WHERE order_id = 'a-1'") shouldBe "大阪府"
            query("SELECT status FROM batch.batch_run WHERE dag_id = 'batch-etl.order-daily' AND partition_key = '2026-10-13'") shouldBe
                "SUCCEEDED"

            placed("a-3", "2026-10-13T03:00:00Z")
            BatchEtlCommands.run(listOf("run", "2026-10-13"), appEnv()) shouldBe BatchEtlCommands.OK
            query("SELECT count(*) FROM mart.order_daily WHERE business_date = '2026-10-13'") shouldBe "2"
            BatchEtlCommands.run(listOf("run", "2026-10-13", "--restart"), appEnv()) shouldBe BatchEtlCommands.OK
            query("SELECT count(*) FROM mart.order_daily WHERE business_date = '2026-10-13'") shouldBe "3"
            query("SELECT generation FROM batch.batch_run WHERE partition_key = '2026-10-13'") shouldBe "2"
        }

        test("serve: 直近の起動の時刻の分(前日)を自動で実行し、ready になる") {
            val yesterday = BusinessDay.of(Clock.System.now()).minusDays(1).toString()
            val server = (BatchEtlServer.start(appEnv() + (BatchEtlConfig.DAILY_AT to "00:00")) as? Result.Ok)?.value ?: fail("起動できません")
            try {
                withTimeout(90.seconds) {
                    while (
                        query(
                            "SELECT status FROM batch.batch_run WHERE dag_id = 'batch-etl.order-daily' AND partition_key = '$yesterday'",
                        ) !=
                        "SUCCEEDED"
                    ) {
                        delay(500)
                    }
                }
                ktor.get("http://localhost:${server.healthPort}/health/ready").status shouldBe HttpStatusCode.OK
                ktor.get("http://localhost:${server.healthPort}/health/live").status shouldBe HttpStatusCode.OK
                query("SELECT count(*) FROM mart.partition_build WHERE business_date = '$yesterday'") shouldBe "1"
            } finally {
                server.stop()
            }
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

// 注文の受け付けのイベント(contracts/avro/sales/OrderCreated.avsc)。テストが送るための型(order-service の代わり)

@Serializable
@SerialName("io.eia.events.sales.OrderCreated")
internal data class CreatedV1(
    val order: OrderV1,
)

@Serializable
@SerialName("io.eia.events.sales.Order")
internal data class OrderV1(
    val id: String,
    val customerId: String,
    val status: StatusV1,
    val orderedAt: Instant,
    val lines: List<LineV1>,
    val totalAmount: MoneyV1,
    val shippingAddress: AddressV1,
)

@Serializable
@SerialName("io.eia.events.sales.OrderStatus")
internal enum class StatusV1 { PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

@Serializable
@SerialName("io.eia.events.sales.OrderLine")
internal data class LineV1(
    val lineNumber: Int,
    val productId: String,
    val sku: String,
    val quantity: Long,
    val unitPrice: MoneyV1,
    val lineAmount: MoneyV1,
)

@Serializable
@SerialName("io.eia.events.common.Money")
internal data class MoneyV1(
    val minorUnits: Long,
    val currency: String,
)

@Serializable
@SerialName("io.eia.events.common.Address")
internal data class AddressV1(
    val countryCode: String,
    val postalCode: String,
    val city: String,
    val line1: String,
    val region: String? = null,
    val line2: String? = null,
)
