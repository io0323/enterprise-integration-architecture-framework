package io.eia.integrationflow.application

import io.eia.integrationflow.application.port.inbound.IngestMessage
import io.eia.integrationflow.application.port.inbound.IngestOutcome
import io.eia.integrationflow.application.port.inbound.SyncReport
import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.application.port.outbound.CanonicalPublisher
import io.eia.integrationflow.application.port.outbound.FlowVersions
import io.eia.integrationflow.application.port.outbound.ProcessedMessages
import io.eia.integrationflow.application.port.outbound.SaasConnector
import io.eia.integrationflow.application.port.outbound.SourcePage
import io.eia.integrationflow.application.port.outbound.SyncWatermarks
import io.eia.integrationflow.application.port.outbound.TransactionRunner
import io.eia.integrationflow.application.usecase.IngestWebhookService
import io.eia.integrationflow.application.usecase.RecordPublisher
import io.eia.integrationflow.application.usecase.SyncFlowService
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.FlowMappingError
import io.eia.integrationflow.domain.MappingNode
import io.eia.integrationflow.domain.MappingRule
import io.eia.integrationflow.domain.Ordering
import io.eia.integrationflow.domain.Route
import io.eia.integrationflow.domain.SourcePath
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.Transform
import io.eia.integrationflow.domain.Trigger
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val TOPIC = "integration.crm-webhook.received.v1"
private val NOW = Instant.parse("2026-10-21T12:00:00Z")

private fun path(text: String) = checkNotNull(SourcePath.parse(text))

private val POLL = Trigger.Poll("INT-SALES-006", "customers", 15.minutes)

private val FLOW: FlowDefinition =
    (
        FlowDefinition.of(
            "crm-customer",
            1,
            "INT-SALES-007",
            "sales.Customer",
            listOf(Trigger.Event(TOPIC, "crm", setOf("customer.created", "customer.updated"), path("$.data")), POLL),
            Ordering("id", path("$.version")),
            mapOf("id" to MappingNode.Field(MappingRule(path("$.id"), Transform.Copy))),
            emptyMap(),
            listOf(Route(null, "sales.customer.changed.v1")),
        ) as Result.Ok
    ).value

private fun customer(
    id: String,
    version: Int,
) = SourceValue.Obj(mapOf("id" to SourceValue.Text(id), "version" to SourceValue.Number(version.toString())))

private fun webhook(
    messageId: String,
    type: String,
    data: SourceValue?,
) = IngestMessage(
    messageId,
    TOPIC,
    "crm",
    SourceValue.Obj(listOfNotNull("type" to SourceValue.Text(type), data?.let { "data" to it }).toMap()),
)

/** DB の代わり: トランザクションの中の書き込みを、Err なら取り消す。 */
private class FakeStore :
    TransactionRunner,
    FlowVersions,
    ProcessedMessages,
    CanonicalPublisher,
    SyncWatermarks {
    var versions = mutableMapOf<Pair<String, String>, Long>()
    var processed = mutableSetOf<String>()
    var published = mutableListOf<Pair<String, Long>>()
    var watermarks = mutableMapOf<Pair<String, String>, Instant>()
    var publishFailure: DomainError? = null

    override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        val snapshot = Triple(versions.toMutableMap(), processed.toMutableSet(), published.toMutableList())
        return block().also {
            if (it is Result.Err) {
                versions = snapshot.first
                processed = snapshot.second
                published = snapshot.third
            }
        }
    }

    override suspend fun advance(
        flowId: String,
        key: String,
        version: Long,
    ): Result<VersionDecision, DomainError> {
        val decision = VersionDecision.of(versions[flowId to key], version)
        if (decision == VersionDecision.NEWER) versions[flowId to key] = version
        return ok(decision)
    }

    override suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError> = ok(processed.add(messageId))

    override suspend fun publish(
        flow: FlowDefinition,
        record: TransformedRecord,
        sourceSystem: String,
    ): Result<Unit, DomainError> {
        publishFailure?.let { return err(it) }
        published += record.key to record.version
        return ok(Unit)
    }

    override suspend fun get(
        flowId: String,
        connector: String,
    ): Result<Instant?, DomainError> = ok(watermarks[flowId to connector])

    override suspend fun set(
        flowId: String,
        connector: String,
        watermark: Instant,
    ): Result<Unit, DomainError> = ok(Unit).also { watermarks[flowId to connector] = watermark }
}

/** ページごとの結果を順に返す Connector。受けた updatedSince を残す。 */
private class FakeConnector(
    vararg pages: Result<SourcePage, DomainError>,
) : SaasConnector {
    private val pages = ArrayDeque(pages.toList())
    val requests = mutableListOf<Pair<Instant?, String?>>()
    override val connector: String = "INT-SALES-006"
    override val sourceSystem: String = "crm"
    override val resources: Set<String> = setOf("customers")

    override suspend fun page(
        resource: String,
        updatedSince: Instant?,
        cursor: String?,
    ): Result<SourcePage, DomainError> {
        requests += updatedSince to cursor
        return pages.removeFirst()
    }
}

class FlowPipelineSpec :
    FunSpec({
        lateinit var store: FakeStore
        lateinit var ingest: IngestWebhookService
        beforeTest {
            store = FakeStore()
            ingest = IngestWebhookService((FlowCatalog.of(listOf(FLOW)) as Result.Ok).value, store, store, RecordPublisher(store, store))
        }

        fun sync(connector: FakeConnector) =
            SyncFlowService(mapOf(connector.connector to connector), store, RecordPublisher(store, store), store, FixedClock(NOW))

        test("Webhook: 新しい版だけを出す。同じ ce_id の再送は何もしない。順序が逆転した古い版は出さない") {
            ingest(webhook("m1", "customer.updated", customer("cus_1", 2))) shouldBe
                ok(IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.NEWER)))
            ingest(webhook("m1", "customer.updated", customer("cus_1", 2))) shouldBe ok(IngestOutcome.Duplicate)
            // 版 1(先に作られた)の Webhook が後から届いた
            ingest(webhook("m0", "customer.created", customer("cus_1", 1))) shouldBe
                ok(IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.STALE)))
            ingest(webhook("m2", "customer.updated", customer("cus_1", 2))) shouldBe
                ok(IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.SAME)))
            store.published shouldBe listOf("cus_1" to 2L)
        }

        test("Webhook: 取り込まない種類は記録して何も出さない。種類・元のレコードがない・写せないのは NonRetryable(冪等消費の記録も取り消す)") {
            ingest(webhook("m1", "customer.deleted", customer("cus_1", 1))) shouldBe ok(IngestOutcome.Ignored("customer.deleted"))
            store.processed shouldBe mutableSetOf("m1")
            (
                ingest(
                    IngestMessage("m2", TOPIC, "crm", SourceValue.Obj(emptyMap())),
                ) as Result.Err
            ).error.shouldBeInstanceOf<FlowMappingError>().field shouldBe
                "$.type"
            (ingest(webhook("m3", "customer.updated", null)) as Result.Err).error.shouldBeInstanceOf<FlowMappingError>().field shouldBe
                "$.data"
            (ingest(webhook("m4", "customer.updated", SourceValue.Obj(mapOf("id" to SourceValue.Text("x"))))) as Result.Err)
                .error
                .shouldBeInstanceOf<FlowMappingError>()
            store.processed shouldBe mutableSetOf("m1")
        }

        test("Webhook: 発行に失敗したら、新旧の記録と冪等消費の記録も取り消す") {
            store.publishFailure = UnavailableError("outbox")
            ingest(webhook("m1", "customer.updated", customer("cus_1", 2))).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            store.versions shouldBe mutableMapOf()
            store.processed shouldBe mutableSetOf()
        }

        test("条件 3: Webhook で新しい版を受けた後に、定期の同期が古い版で上書きしない(同じ記録を使う)") {
            ingest(webhook("m1", "customer.updated", customer("cus_1", 3)))
            val connector =
                FakeConnector(
                    ok(SourcePage(listOf(customer("cus_1", 2), customer("cus_2", 1)), "c1")),
                    ok(SourcePage(listOf(customer("cus_1", 3)), null)),
                )
            sync(connector)(FLOW, POLL) shouldBe ok(SyncReport(pages = 2, published = 1, same = 1, stale = 1, failed = 0))
            store.published shouldBe listOf("cus_1" to 3L, "cus_2" to 1L)
            store.versions[FLOW.id to "cus_1"] shouldBe 3
            connector.requests shouldBe listOf(null to null, null to "c1")
            // Watermark は同期を始めた時刻から重なりの分を引いた時刻
            store.watermarks[FLOW.id to "INT-SALES-006"] shouldBe NOW - SyncFlowService.DEFAULT_OVERLAP
        }

        test("同期: 次の回は Watermark の後から読む。写せないレコードは数えて進むが、Watermark は進めない") {
            store.watermarks[FLOW.id to "INT-SALES-006"] = NOW - 1.minutes
            val bad = SourceValue.Obj(mapOf("id" to SourceValue.Text("cus_9")))
            val connector = FakeConnector(ok(SourcePage(listOf(bad, customer("cus_1", 1)), null)))
            sync(connector)(FLOW, POLL) shouldBe ok(SyncReport(pages = 1, published = 1, same = 0, stale = 0, failed = 1))
            connector.requests shouldBe listOf((NOW - 1.minutes) to null)
            store.watermarks[FLOW.id to "INT-SALES-006"] shouldBe NOW - 1.minutes
        }

        test("同期: Connector・発行の Retryable の失敗はそこで止めて Err(Watermark は進めない)。NonRetryable の発行の失敗は数えて進む") {
            val failing = FakeConnector(ok(SourcePage(listOf(customer("cus_1", 1)), "c1")), err(UnavailableError("429")))
            sync(failing)(FLOW, POLL).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            store.watermarks.isEmpty() shouldBe true

            store.publishFailure = UnavailableError("db")
            sync(
                FakeConnector(ok(SourcePage(listOf(customer("cus_2", 1)), null))),
            )(FLOW, POLL).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            store.publishFailure = UnexpectedError("canonical")
            sync(FakeConnector(ok(SourcePage(listOf(customer("cus_3", 1)), null))))(FLOW, POLL) shouldBe
                ok(SyncReport(pages = 1, published = 0, same = 0, stale = 0, failed = 1))
            store.watermarks.isEmpty() shouldBe true

            (SyncFlowService(emptyMap(), store, RecordPublisher(store, store), store)(FLOW, POLL) as Result.Err).error.code shouldBe
                "unexpected"
        }
    })
