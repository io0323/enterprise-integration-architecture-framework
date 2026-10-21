@file:Suppress("MagicNumber") // 版・件数・乱数のバイト数

package io.eia.integrationflow.adapters

import io.eia.integrationflow.adapters.flow.YamlFlowDefinitions
import io.eia.integrationflow.adapters.out.persistence.ExposedTransactionRunner
import io.eia.integrationflow.adapters.out.persistence.InboxProcessedMessages
import io.eia.integrationflow.adapters.out.persistence.IntegrationFlowSchema
import io.eia.integrationflow.adapters.out.persistence.JdbcFlowVersions
import io.eia.integrationflow.adapters.out.persistence.JdbcSyncWatermarks
import io.eia.integrationflow.application.port.inbound.IngestMessage
import io.eia.integrationflow.application.port.inbound.IngestOutcome
import io.eia.integrationflow.application.port.inbound.SyncReport
import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.application.port.outbound.CanonicalPublisher
import io.eia.integrationflow.application.port.outbound.SaasConnector
import io.eia.integrationflow.application.port.outbound.SourcePage
import io.eia.integrationflow.application.usecase.IngestWebhookService
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.integrationflow.application.usecase.RecordPublisher
import io.eia.integrationflow.application.usecase.SyncFlowService
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.Trigger
import io.eia.integrationflow.domain.VersionDecision
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.SQLException
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val OWNER_ROLE = "integration_flow"
private const val APP_ROLE = "integration_flow_app"
private const val TOPIC = "integration.crm-webhook.received.v1"
private val NOW = Instant.parse("2026-10-21T12:00:00Z")

/** 出した版を残す(Outbox への書き込みは app の統合テストで確かめる)。 */
private class RecordingPublisher : CanonicalPublisher {
    private val mutex = Mutex()
    val published = mutableListOf<Triple<String, Long, String>>()
    var failure: DomainError? = null

    override suspend fun publish(
        flow: FlowDefinition,
        record: TransformedRecord,
        sourceSystem: String,
    ): Result<Unit, DomainError> =
        mutex.withLock {
            failure?.let { return@withLock err(it) }
            published += Triple(record.key, record.version, sourceSystem)
            ok(Unit)
        }
}

/** 決めたページを返す CRM の代わり。 */
private class ListConnector(
    private val pages: List<List<SourceValue>>,
) : SaasConnector {
    override val connector: String = "INT-SALES-006"
    override val sourceSystem: String = "crm"
    override val resources: Set<String> = setOf("customers")

    override suspend fun page(
        resource: String,
        updatedSince: Instant?,
        cursor: String?,
    ): Result<SourcePage, DomainError> {
        val index = cursor?.toInt() ?: 0
        return ok(SourcePage(pages[index], if (index + 1 < pages.size) "${index + 1}" else null))
    }
}

private fun crmCustomer(
    id: String,
    version: Int,
): SourceValue =
    SourceValue.Obj(
        mapOf(
            "id" to SourceValue.Text(id),
            "display_name" to SourceValue.Text("Name $id"),
            "email" to SourceValue.Text("$id@example.com"),
            "created_at" to SourceValue.Text("2026-10-21T00:00:00Z"),
            "updated_at" to SourceValue.Text("2026-10-21T0$version:00:00Z"),
            "version" to SourceValue.Number("$version"),
        ),
    )

private fun webhook(
    type: String,
    customer: SourceValue,
    messageId: Uuid = Uuid.random(),
) = IngestMessage(messageId.toString(), TOPIC, "crm", SourceValue.Obj(mapOf("type" to SourceValue.Text(type), "data" to customer)))

/** 新旧の判定の記録・冪等消費・Watermark を、実際の PostgreSQL で確かめる(ADR-0035 §4・ADR-0036 §3・§4)。ロールはローカル基盤と同じ構成。 */
class FlowPersistenceIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .also { it.start() }
        afterSpec { postgres.stop() }
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val databases = AtomicInteger()
        val catalog = LoadFlowsService(YamlFlowDefinitions())().ok()
        val flow = catalog.flows.single()
        val poll = flow.triggers.filterIsInstance<Trigger.Poll>().single()

        fun dataSource(
            user: String,
            password: String,
            database: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun superuser(database: String = postgres.databaseName) = dataSource(postgres.username, postgres.password, database)

        superuser().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute("CREATE ROLE $APP_ROLE LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                statement.execute("CREATE ROLE ${IntegrationFlowSchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '${randomHex()}'")
            }
        }

        class Service(
            val name: String,
        ) {
            val database = Database.connect(dataSource(APP_ROLE, appPassword, name))
            val publisher = RecordingPublisher()
            val transactions = ExposedTransactionRunner(database)
            val versions = JdbcFlowVersions(database)
            val records = RecordPublisher(versions, publisher)
            val ingest =
                IngestWebhookService(catalog, transactions, InboxProcessedMessages(database, "integration-flow.webhook-ingest"), records)

            fun sync(vararg pages: List<SourceValue>) =
                SyncFlowService(
                    mapOf("INT-SALES-006" to ListConnector(pages.toList())),
                    transactions,
                    records,
                    JdbcSyncWatermarks(database),
                    FixedClock(NOW),
                )

            fun strings(sql: String): List<String> =
                superuser(name).connection.use { c ->
                    c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
                }
        }

        fun newService(): Service {
            val name = "integration_flow_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO $APP_ROLE")
                }
            }
            val owner = dataSource(OWNER_ROLE, ownerPassword, name)
            IntegrationFlowSchema.migrate(owner, APP_ROLE).ok()
            IntegrationFlowSchema.migrate(owner, APP_ROLE).ok()
            return Service(name)
        }

        test("条件 3: Webhook で新しい版を受けた後に、定期の同期が古い版で上書きしない(Webhook と同期が同じ記録を使う)") {
            val service = newService()
            service.ingest(webhook("customer.updated", crmCustomer("cus_1", 3))).ok() shouldBe
                IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.NEWER))
            // 定期の同期: cus_1 は古い版 2(同期の API の結果が Webhook より古い)、cus_2 は新しい顧客
            service
                .sync(
                    listOf(crmCustomer("cus_1", 2)),
                    listOf(crmCustomer("cus_2", 1), crmCustomer("cus_1", 3)),
                )(flow, poll)
                .ok() shouldBe
                SyncReport(pages = 2, published = 1, same = 1, stale = 1, failed = 0)
            service.publisher.published shouldBe listOf(Triple("cus_1", 3L, "crm"), Triple("cus_2", 1L, "crm"))
            service.strings("SELECT record_key || ':' || version FROM flow_version ORDER BY record_key") shouldBe
                listOf("cus_1:3", "cus_2:1")
            // 同期の後に届いた古い Webhook(順序の逆転)も出さない。新しい版は出す
            service.ingest(webhook("customer.created", crmCustomer("cus_2", 0))).ok() shouldBe
                IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.STALE))
            service.ingest(webhook("customer.updated", crmCustomer("cus_1", 4))).ok() shouldBe
                IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.NEWER))
            service.strings("SELECT version FROM flow_version WHERE record_key = 'cus_1'") shouldBe listOf("4")
            // Watermark は同期を始めた時刻から重なりの分を引いた時刻
            service.strings("SELECT to_char(watermark AT TIME ZONE 'UTC', 'HH24:MI') FROM sync_watermark") shouldBe listOf("11:55")
        }

        test("条件 4: 同じ ce_id の再送は processed_message で止める。失敗したら冪等消費の記録も残らない(読み直しで処理できる)") {
            val service = newService()
            val id = Uuid.random()
            service.publisher.failure = UnavailableError("outbox")
            service.ingest(webhook("customer.updated", crmCustomer("cus_1", 1), id)).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            service.strings("SELECT count(*) FROM inbox.processed_message") shouldBe listOf("0")
            service.strings("SELECT count(*) FROM flow_version") shouldBe listOf("0")

            service.publisher.failure = null
            service.ingest(webhook("customer.updated", crmCustomer("cus_1", 1), id)).ok() shouldBe
                IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.NEWER))
            service.ingest(webhook("customer.updated", crmCustomer("cus_1", 1), id)).ok() shouldBe IngestOutcome.Duplicate
            service.publisher.published.size shouldBe 1
            service.strings("SELECT consumer_group FROM inbox.processed_message") shouldBe listOf("integration-flow.webhook-ingest")
            // ce_id が UUID でなければ、契約違反として扱う
            service
                .ingest(
                    webhook("customer.updated", crmCustomer("cus_1", 2)).let { IngestMessage("not-a-uuid", it.topic, it.source, it.body) },
                ).shouldBeInstanceOf<Result.Err<DomainError.NonRetryable>>()
        }

        test("同じキーの同時の更新: 版の大きい方だけが残り、新しい版を出すのは 1 回だけ") {
            val service = newService()
            val outcomes =
                coroutineScope {
                    (1..6)
                        .map { n ->
                            async {
                                service.ingest(
                                    webhook(
                                        "customer.updated",
                                        crmCustomer(
                                            "cus_1",
                                            if (n % 2 ==
                                                0
                                            ) {
                                                5
                                            } else {
                                                4
                                            },
                                        ),
                                    ),
                                )
                            }
                        }.awaitAll()
                }
            outcomes.forEach { it.shouldBeInstanceOf<Result.Ok<*>>() }
            service.strings("SELECT version FROM flow_version") shouldBe listOf("5")
            service.publisher.published.count { it.second == 5L } shouldBe 1
        }

        test("アプリのロールは新旧の判定の記録を消せない(SELECT / INSERT / UPDATE だけ)") {
            val service = newService()
            service.ingest(webhook("customer.updated", crmCustomer("cus_1", 1))).ok()
            dataSource(APP_ROLE, appPassword, service.name).connection.use { c ->
                shouldThrow<SQLException> { c.createStatement().use { it.execute("DELETE FROM flow_version") } }.sqlState shouldBe "42501"
            }
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
