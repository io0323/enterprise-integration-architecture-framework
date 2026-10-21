package io.eia.integrationflow.adapters

import com.github.avrokotlin.avro4k.Avro
import io.eia.integrationflow.adapters.flow.YamlFlowDefinitions
import io.eia.integrationflow.adapters.inbound.kafka.WebhookIngestHandler
import io.eia.integrationflow.adapters.inbound.kafka.WebhookReceivedV1
import io.eia.integrationflow.adapters.out.outbox.AddressV1
import io.eia.integrationflow.adapters.out.outbox.CustomerChangedV1
import io.eia.integrationflow.adapters.out.outbox.CustomerV1
import io.eia.integrationflow.adapters.out.outbox.FlowEventSchemas
import io.eia.integrationflow.adapters.out.persistence.TransientSqlError
import io.eia.integrationflow.adapters.out.saas.CrmCustomersConnector
import io.eia.integrationflow.application.port.inbound.IngestMessage
import io.eia.integrationflow.application.port.inbound.IngestOutcome
import io.eia.integrationflow.application.port.inbound.IngestWebhookUseCase
import io.eia.integrationflow.application.port.inbound.SyncReport
import io.eia.integrationflow.application.port.outbound.SourcePage
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.integrationflow.domain.FlowMappingError
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.VersionDecision
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.sales.Customer
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.apache.avro.Schema
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun contract(path: String): String = File(checkNotNull(System.getProperty("eia.contractsAvro")), path).readText()

private val ID = Uuid.parse("0199b6a0-0000-7000-8000-000000000001")
private val CATALOG: FlowCatalog = LoadFlowsService(YamlFlowDefinitions())().ok()

private fun event(body: String): ConsumedEvent<WebhookReceivedV1> =
    ConsumedEvent(
        EventMetadata(
            ID,
            "/integration/webhook-receiver",
            "integration.crm-webhook.received",
            Instant.parse("2026-10-21T00:00:00Z"),
            (TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") as Result.Ok).value,
            (CorrelationId.parse("corr-1") as Result.Ok).value,
        ),
        "evt_1",
        WebhookReceivedV1(
            "evt_1",
            "crm",
            Instant.parse("2026-10-21T00:00:00Z"),
            Instant.parse("2026-10-21T00:00:01Z"),
            "application/json",
            "a".repeat(64),
            body.toByteArray(),
        ),
        "integration.crm-webhook.received.v1",
        0,
        0,
    )

private class FakeIngest(
    var result: Result<IngestOutcome, DomainError> = ok(IngestOutcome.Duplicate),
) : IngestWebhookUseCase {
    val messages = mutableListOf<IngestMessage>()

    override suspend fun invoke(message: IngestMessage): Result<IngestOutcome, DomainError> = result.also { messages += message }
}

private class Metrics {
    private val reader = InMemoryMetricReader.create()
    val meter =
        SdkMeterProvider
            .builder()
            .registerMetricReader(reader)
            .build()
            .get("test")

    /** メトリクスの名前ごとの、属性の値を属性の名前の順に `/` でつないだもの(例 `flow/outcome/path`)と件数。 */
    fun points(name: String): Map<String, Long> =
        reader
            .collectAllMetrics()
            .filter { it.name == name }
            .flatMap { metric -> metric.longSumData.points }
            .associate { point ->
                point.attributes
                    .asMap()
                    .values
                    .joinToString("/") to point.value
            }
}

class FlowConnectionsSpec :
    FunSpec({
        test("読むイベントと書くイベントのスキーマは契約のファイルと同じで、型を契約のスキーマで読み書きできる(時刻はマイクロ秒)") {
            FlowEventSchemas.subjects.map { it.topic } shouldBe listOf("sales.customer.changed.v1")
            FlowEventSchemas.customerChanged.schema shouldBe contract("sales/CustomerChanged.avsc")
            val changedSchema = Schema.Parser().parse(FlowEventSchemas.customerChanged.schema)
            val customer =
                Customer(
                    CustomerId("cus_1"),
                    "Alice",
                    "alice@example.com",
                    Instant.parse("2026-10-21T00:00:00.123456789Z"),
                    Instant.parse("2026-10-21T01:00:00Z"),
                    Address("JP", "100-0001", "Chiyoda", "1-1"),
                )
            val event = CustomerChangedV1(CustomerV1.of(customer), "crm", 3)
            event.customer.createdAt shouldBe Instant.parse("2026-10-21T00:00:00.123456Z")
            event.customer.billingAddress shouldBe AddressV1("JP", "100-0001", "Chiyoda", "1-1")
            Avro.decodeFromByteArray(
                changedSchema,
                CustomerChangedV1.serializer(),
                Avro.encodeToByteArray(changedSchema, CustomerChangedV1.serializer(), event),
            ) shouldBe
                event
            event.toString() shouldNotContain "alice"

            val receivedSchema = Schema.Parser().parse(contract("integration/WebhookReceived.avsc"))
            val received = event("""{"type":"customer.updated"}""").value
            Avro
                .decodeFromByteArray(
                    receivedSchema,
                    WebhookReceivedV1.serializer(),
                    Avro.encodeToByteArray(receivedSchema, WebhookReceivedV1.serializer(), received),
                ).body
                .decodeToString() shouldBe """{"type":"customer.updated"}"""
            received.toString() shouldNotContain "customer.updated"
        }

        test("購読: Flow の event の trigger のトピックを読む。結果を Handled に写して数える") {
            val metrics = Metrics()
            val ingest = FakeIngest()
            val handler = WebhookIngestHandler(CATALOG, ingest, FlowMetrics(metrics.meter))
            handler.topics.map { (topic, integration) -> topic.name to integration } shouldBe
                listOf("integration.crm-webhook.received.v1" to "INT-SALES-007")
            WebhookIngestHandler.GROUP_ID shouldBe "integration-flow.webhook-ingest"

            handler.handle(event("""{"type":"customer.updated","data":{}}""")) shouldBe Result.Ok(Handled.DUPLICATE)
            ingest.messages.single().let {
                it.messageId shouldBe ID.toString()
                it.source shouldBe "crm"
                it.topic shouldBe "integration.crm-webhook.received.v1"
                (it.body as SourceValue.Obj).fields.keys shouldBe setOf("type", "data")
            }
            ingest.result = ok(IngestOutcome.Ignored("customer.deleted"))
            handler.handle(event("""{"type":"customer.deleted"}""")) shouldBe Result.Ok(Handled.PROCESSED)
            ingest.result =
                ok(IngestOutcome.Processed(listOf("crm-customer" to VersionDecision.NEWER, "crm-customer" to VersionDecision.STALE)))
            handler.handle(event("""{"type":"customer.updated"}""")) shouldBe Result.Ok(Handled.PROCESSED)
            metrics.points("eia.flow.records") shouldBe
                mapOf(
                    "none/duplicate/webhook" to 1L,
                    "none/ignored/webhook" to 1L,
                    "crm-customer/published/webhook" to 1L,
                    "crm-customer/stale/webhook" to 1L,
                )
        }

        test("購読: 本文が JSON のオブジェクトでなければ DLQ。写せないは DLQ・直列化の失敗はその場でリトライ・DB に届かないは読み直し") {
            val metrics = Metrics()
            val ingest = FakeIngest()
            val handler = WebhookIngestHandler(CATALOG, ingest, FlowMetrics(metrics.meter))
            (handler.handle(event("not json")) as Result.Err).error shouldBe
                HandlingFailure.Rejected("webhook_body_invalid", "本文が UTF-8 の JSON のオブジェクトではありません")
            ingest.messages.size shouldBe 0

            suspend fun failure(error: DomainError): HandlingFailure {
                ingest.result = err(error)
                return (handler.handle(event("{}")) as Result.Err).error
            }
            failure(FlowMappingError("email", "文字列ではありません")) shouldBe HandlingFailure.Rejected("flow_mapping_failed", "email: 文字列ではありません")
            failure(TransientSqlError("40001")).shouldBeInstanceOf<HandlingFailure.Transient>()
            failure(UnavailableError("08006")).shouldBeInstanceOf<HandlingFailure.Unavailable>()
            failure(UnexpectedError("23514")).shouldBeInstanceOf<HandlingFailure.Rejected>()
            metrics.points("eia.flow.records") shouldBe mapOf("none/failed/webhook" to 3L)
        }

        context("CRM の顧客の Connector") {
            val api = CrmCustomersConnector.CrmApi(URI("http://crm.test/"), SecretName("CRM_TOKEN"), pageSize = 2)
            val secrets = EnvSecretProvider(mapOf("CRM_TOKEN" to "api-token"))
            val fast =
                ResilienceConfig(
                    attemptTimeout = 5.seconds,
                    retry = RetryPolicy(initialDelay = 10.milliseconds, maxAttempts = 3, maxDelay = 2.seconds),
                )

            fun connector(
                metrics: Metrics,
                handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
            ) = CrmCustomersConnector(
                HttpClient(MockEngine(handler)),
                api,
                secrets,
                ResilienceMetrics(metrics.meter).resilience(CrmCustomersConnector.RESILIENCE_NAME, fast),
            )

            test("ページングの引数と Bearer トークン。応答は Tolerant Reader(data と next_cursor だけを読み、知らない項目は無視)") {
                val requests = mutableListOf<HttpRequestData>()
                val crm =
                    connector(Metrics()) { request ->
                        requests += request
                        respond(
                            """{"data":[{"id":"cus_1","version":2,"loyalty_tier":"gold"}],""" +
                                """"next_cursor":"Y3VzXzE","has_more":true,"meta":{"x":1}}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val page = crm.page("customers", Instant.parse("2026-10-21T00:00:00Z"), "c0").ok()
                page.nextCursor shouldBe "Y3VzXzE"
                (page.records.single() as SourceValue.Obj).fields["loyalty_tier"] shouldBe SourceValue.Text("gold")
                val request = requests.single()
                request.url.toString() shouldBe "http://crm.test/v1/customers?limit=2&updated_since=2026-10-21T00%3A00%3A00Z&cursor=c0"
                request.headers[HttpHeaders.Authorization] shouldBe "Bearer api-token"
                crm.connector shouldBe "INT-SALES-006"
                crm.sourceSystem shouldBe "crm"
            }

            test("429 は Retry-After の秒数を待ってから再試行する(eia.resilience.retries)。401 はリトライしない") {
                val metrics = Metrics()
                var calls = 0
                val crm =
                    connector(metrics) {
                        calls++
                        if (calls == 1) {
                            respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                        } else {
                            respond("""{"data":[],"next_cursor":null}""", HttpStatusCode.OK)
                        }
                    }
                val started =
                    kotlin.time.TimeSource.Monotonic
                        .markNow()
                crm.page("customers", null, null).ok().records shouldBe emptyList()
                (started.elapsedNow() >= 1.seconds) shouldBe true
                calls shouldBe 2
                metrics.points("eia.resilience.retries") shouldBe mapOf("saas-crm" to 1L)

                var unauthorized = 0
                val denied =
                    connector(Metrics()) {
                        unauthorized++
                        respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
                    }
                denied.page("customers", null, null).shouldBeInstanceOf<Result.Err<DomainError.NonRetryable>>()
                unauthorized shouldBe 1
            }

            test("応答の形が契約と違えば NonRetryable。トークンを読めなければ Retryable。知らない対象は誤り") {
                listOf("""{"items":[]}""", """{"data":[1]}""", """{"data":[],"next_cursor":5}""", "not json").forEach { body ->
                    connector(Metrics()) { respond(body, HttpStatusCode.OK) }
                        .page("customers", null, null)
                        .shouldBeInstanceOf<Result.Err<UnexpectedError>>()
                }
                val noToken =
                    CrmCustomersConnector(
                        HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
                        api,
                        EnvSecretProvider(emptyMap()),
                        ResilienceMetrics(Metrics().meter).resilience("saas-crm-no-token", fast.copy(retry = null)),
                    )
                noToken.page("customers", null, null).shouldBeInstanceOf<Result.Err<UnavailableError>>()
                noToken.page("orders", null, null).shouldBeInstanceOf<Result.Err<UnexpectedError>>()
                SourcePage(emptyList(), null).nextCursor shouldBe null
            }
        }

        test("同期の結果のメトリクス: 件数と回の結果(写せないレコードがあれば partial、失敗は failed)") {
            val metrics = Metrics()
            val flowMetrics = FlowMetrics(metrics.meter)
            flowMetrics.synced("crm-customer", SyncReport(pages = 2, published = 3, same = 1, stale = 0, failed = 0))
            flowMetrics.synced("crm-customer", SyncReport(pages = 1, published = 0, same = 0, stale = 0, failed = 2))
            flowMetrics.synced("crm-customer", null)
            metrics.points("eia.flow.sync.runs") shouldBe
                mapOf("crm-customer/succeeded" to 1L, "crm-customer/partial" to 1L, "crm-customer/failed" to 1L)
            metrics.points("eia.flow.records") shouldBe
                mapOf("crm-customer/published/poll" to 3L, "crm-customer/same/poll" to 1L, "crm-customer/failed/poll" to 2L)
        }
    })
