@file:Suppress("MagicNumber") // 件数・待ち時間・時刻

package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.adapters.inbound.LegacyChangeConsumer
import io.eia.legacyorderacl.adapters.outbound.KafkaLegacyOrderStatePublisher
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.application.port.inbound.ChangePosition
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.legacyorderacl.domain.LegacyOrderStatus
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val SHORT = ReconcileWaits(timeout = 3.seconds, poll = 200.milliseconds)
private val ORDERED_AT = Instant.parse("2026-10-08T00:00:00Z")
private val POSITION = ChangePosition(1, ORDERED_AT, false)

private fun order(
    number: String,
    status: LegacyOrderStatus,
) = LegacyOrder(number, "C0000101", "山田商事株式会社", status, Money.ofMinor(1200, Currency.JPY), ORDERED_AT, null)

/**
 * 照合のアダプタ(ADR-0027)を、PostgreSQL(論理レプリケーションのスロット)と Kafka・Apicurio で確かめる。
 * 全体(Debezium と ACL を含む)は app の `ReconcileIT`。ここでは、各アダプタの読み取り・待ち・失敗の扱いを個別に確かめる。
 */
class ReconcileAdaptersIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val registry = ApicurioRegistryContainer()
        val http = HttpClient(CIO)
        val runtime = Observability.init(ObservabilityConfig.of("reconcile-adapters-it").ok(), TelemetrySinks(), installLogAppender = false)
        lateinit var admin: Admin

        fun dataSource() =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }

        fun sql(statement: String) = dataSource().connection.use { c -> c.createStatement().use { it.execute(statement) } }

        fun openTransactions(): Long =
            dataSource().connection.use { c ->
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND datname = current_database() " +
                                "AND (state LIKE 'idle in transaction%' OR backend_xmin IS NOT NULL)",
                        ).use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                }
            }

        fun published(waits: ReconcileWaits = ReconcileWaits(30.seconds, 200.milliseconds)) =
            KafkaPublishedLegacyOrders(
                admin = admin,
                consumers = { KafkaConsumer(PublishedReaderSettings.properties(kafka.bootstrapServers)) },
                writerSchemas = WriterSchemas(ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl), http)),
                waits = waits,
            )

        /** ACL の Consumer Group が、生の CDC の各パーティションを [offsets] まで処理したことにする(コミット済みのオフセット)。 */
        fun commitAcl(offsets: Long) {
            val partitions = (0..2).associate { TopicPartition(LegacyChangeConsumer.TOPIC, it) to OffsetAndMetadata(offsets) }
            admin.alterConsumerGroupOffsets(LegacyChangeConsumer.GROUP_ID, partitions).all().get()
        }

        beforeSpec {
            postgres.start()
            kafka.start()
            registry.start()
            // レガシーの受注表(legacy-sim の V1 と同じ列)とスロット
            sql(
                """
                CREATE TABLE t_juchu (
                    col_01 NUMERIC(10) NOT NULL PRIMARY KEY, col_02 CHAR(10) NOT NULL UNIQUE, col_03 CHAR(1) NOT NULL,
                    col_04 CHAR(40) NOT NULL, col_05 CHAR(8) NOT NULL, col_06 NUMERIC(13, 2) NOT NULL,
                    col_07 TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
                    col_08 TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT '9999-12-31 00:00:00')
                """.trimIndent(),
            )
            sql(
                "INSERT INTO t_juchu (col_01, col_02, col_03, col_04, col_05, col_06, col_07) VALUES " +
                    "(1, 'J000000001', '1', '山田商事株式会社', 'C0000101', 1200.00, '2026-10-08 09:00:00.123456'), " +
                    "(2, 'J000000002', '2', '佐藤製作所', 'C0000102', 3000.00, '2026-10-08 10:00:00')",
            )
            sql("SELECT pg_create_logical_replication_slot('legacy_juchu', 'pgoutput')")
            admin = Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))
            admin
                .createTopics(
                    listOf(
                        NewTopic(LegacyChangeConsumer.TOPIC, 3, 1.toShort()),
                        NewTopic(LegacyOrderEventSchemas.LEGACY_ORDER_CHANGED.name, 3, 1.toShort()),
                    ),
                ).all()
                .get()
            ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl), http).register(LegacyOrderEventSchemas.legacyOrderChanged).ok()
        }

        afterSpec {
            admin.close()
            http.close()
            runtime.close()
            registry.stop()
            kafka.stop()
            postgres.stop()
        }

        context("JdbcLegacySource") {
            test("1 つのスナップショットで、位置と行を Debezium の生の CDC と同じ形で読み、トランザクションを閉じてから戻る") {
                val snapshot = JdbcLegacySource(dataSource(), waits = SHORT).read(ReconcileScope.All).ok()

                snapshot.position shouldMatch Regex("^[0-9A-F]+/[0-9A-F]+$")
                val first = snapshot.rows.single { it.orderNumber == "J000000001" }
                first.customerName shouldBe "山田商事株式会社".padEnd(40)
                first.amount shouldBe "1200.00"
                // 現地時刻を UTC のエポックとみなしたマイクロ秒(2026-10-08T09:00:00.123456)
                first.orderedAtLocalMicros shouldBe 1_791_450_000_123_456L
                first.updatedAtLocalMicros shouldBe 253_402_214_400_000_000L
                openTransactions() shouldBe 0
            }

            test("キーを指定すると、その注文番号だけを読む(固定長の文字列の比較)") {
                JdbcLegacySource(dataSource(), waits = SHORT)
                    .read(ReconcileScope.Keys(setOf("J000000002", "J999999999")))
                    .ok()
                    .rows
                    .map { it.orderNumber } shouldBe listOf("J000000002")
            }

            test("スロットが位置より先まで進んでいれば待たずに戻り、進まなければ上限で一時的な失敗にする") {
                val source = JdbcLegacySource(dataSource(), waits = SHORT)
                source.awaitCaptured("0/0") shouldBe Result.Ok(Unit)
                source
                    .awaitCaptured(
                        "FFFFFFFF/0",
                    ).shouldBeInstanceOf<Result.Err<DomainError>>()
                    .error
                    .shouldBeInstanceOf<UnavailableError>()
                JdbcLegacySource(dataSource(), slotName = "missing", waits = SHORT)
                    .awaitCaptured("0/0")
                    .shouldBeInstanceOf<Result.Err<DomainError>>()
            }

            test("表がない(権限の誤りなど)は、値を含まない予期しない失敗にする") {
                sql("CREATE DATABASE empty_db")
                val empty =
                    PGSimpleDataSource().apply {
                        setURL(postgres.jdbcUrl.replaceAfterLast('/', "empty_db"))
                        user = postgres.username
                        password = postgres.password
                    }
                val error =
                    JdbcLegacySource(
                        empty,
                        waits = SHORT,
                    ).read(ReconcileScope.All).shouldBeInstanceOf<Result.Err<DomainError>>().error
                error.shouldBeInstanceOf<UnexpectedError>().message shouldBe "レガシーの DB の読み取りに失敗しました(SQLState 42P01)"
                val unreachable = PGSimpleDataSource().apply { setURL("jdbc:postgresql://127.0.0.1:1/x") }
                JdbcLegacySource(unreachable, waits = SHORT)
                    .read(ReconcileScope.All)
                    .shouldBeInstanceOf<Result.Err<DomainError>>()
                    .error
                    .shouldBeInstanceOf<UnavailableError>()
            }
        }

        context("KafkaPublishedLegacyOrders") {
            test("ACL が生の CDC の末尾まで処理していれば、出力のキーごとの最新の値を読む(tombstone は削除)") {
                KafkaProducer(
                    KafkaProducerSettings(kafka.bootstrapServers, "reconcile-it").toProperties(),
                    ByteArraySerializer(),
                    ByteArraySerializer(),
                ).use { producer ->
                    val ids =
                        SchemaIdBook(
                            LegacyOrderEventSchemas.subjects,
                            ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl), http),
                        )
                    ids.resolve().ok()
                    val publisher =
                        KafkaLegacyOrderStatePublisher(EventProducer(producer, runtime, KafkaLegacyOrderStatePublisher.SOURCE), ids)
                    publisher.upsert(order("J000000001", LegacyOrderStatus.ACCEPTED), POSITION).ok()
                    publisher.upsert(order("J000000001", LegacyOrderStatus.SHIPPED), POSITION).ok()
                    publisher.upsert(order("J000000002", LegacyOrderStatus.ACCEPTED), POSITION).ok()
                    publisher.delete("J000000002", POSITION).ok()
                    publisher.upsert(order("J000000003", LegacyOrderStatus.ALLOCATED), POSITION).ok()
                    // 生の CDC に 1 件(ACL がそれを処理したことにする)
                    producer.send(ProducerRecord(LegacyChangeConsumer.TOPIC, 0, "J000000001".toByteArray(), byteArrayOf(1))).get()
                }
                commitAcl(1)

                val latest = published().readCaughtUp(ReconcileScope.All).ok()
                latest.mapValues { it.value.status } shouldBe
                    mapOf("J000000001" to LegacyOrderStatus.SHIPPED, "J000000003" to LegacyOrderStatus.ALLOCATED)
                latest.getValue("J000000001") shouldBe order("J000000001", LegacyOrderStatus.SHIPPED)
                published().readCaughtUp(ReconcileScope.Keys(setOf("J000000003"))).ok().keys shouldBe setOf("J000000003")
            }

            test("ACL が生の CDC の末尾まで処理していなければ、上限で一時的な失敗にする") {
                KafkaProducer(
                    KafkaProducerSettings(kafka.bootstrapServers, "reconcile-it").toProperties(),
                    ByteArraySerializer(),
                    ByteArraySerializer(),
                ).use { it.send(ProducerRecord(LegacyChangeConsumer.TOPIC, 1, "J000000004".toByteArray(), byteArrayOf(1))).get() }
                commitAcl(0)
                published(
                    SHORT,
                ).readCaughtUp(
                    ReconcileScope.All,
                ).shouldBeInstanceOf<Result.Err<DomainError>>()
                    .error
                    .shouldBeInstanceOf<UnavailableError>()
            }
        }
    })
