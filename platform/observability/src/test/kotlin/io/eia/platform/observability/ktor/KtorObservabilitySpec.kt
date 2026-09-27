package io.eia.platform.observability.ktor

import io.eia.platform.observability.TestTelemetry
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.context.LogKeys
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.ktor.client.ClientObservability
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.observability.ok
import io.eia.shared.kernel.CorrelationId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.semconv.HttpAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val CORRELATION = AttributeKey.stringKey(LogKeys.CORRELATION_ID)
private const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
private const val PARENT_ID = "00f067aa0ba902b7"
private val logger = LoggerFactory.getLogger("io.eia.platform.observability.ktor.KtorObservabilitySpec")

/** ハンドラの中から見えた値(MDC と、スレッドを変えた後の MDC)。 */
private data class Seen(
    val mdc: Map<String, String?>,
    val mdcOnIo: Map<String, String?>,
)

private fun mdcSnapshot(): Map<String, String?> = LogKeys.ALL.associateWith { MDC.get(it) }

private fun ApplicationTestBuilder.observedApp(
    telemetry: TestTelemetry,
    seen: MutableList<Seen> = mutableListOf(),
    downstream: (() -> HttpClient)? = null,
) {
    application {
        install(ServerObservability) {
            runtime = telemetry.runtime
            integrationId = "INT-TEST-001"
        }
        routing {
            get("/v1/orders/{id}") {
                val before = mdcSnapshot()
                val onIo = withContext(Dispatchers.IO) { mdcSnapshot() }
                seen += Seen(before, onIo)
                logger.info("注文を照会しました")
                call.respondText("ok")
            }
            get("/v1/relay") {
                val body = checkNotNull(downstream)().get("/v1/orders/42").bodyAsText()
                call.respondText(body)
            }
            get("/v1/fail") { error("失敗 password=hunter2") }
            get("/v1/missing") { call.respondText("nf", status = HttpStatusCode.NotFound) }
        }
    }
}

private fun List<SpanData>.single(
    kind: SpanKind,
    route: String,
): SpanData = filter { it.kind == kind && it.attributes.get(HttpAttributes.HTTP_ROUTE) == route }.shouldHaveSize(1).first()

class KtorObservabilitySpec :
    FunSpec({
        context("Server プラグイン") {
            test("受信した traceparent を親として SERVER span を作り、route テンプレートで名前を付ける") {
                TestTelemetry().use { telemetry ->
                    testApplication {
                        observedApp(telemetry)
                        client.get("/v1/orders/42") { header("traceparent", "00-$TRACE_ID-$PARENT_ID-01") }
                    }
                    val span = telemetry.spans.single(SpanKind.SERVER, "/v1/orders/{id}")

                    span.traceId shouldBe TRACE_ID
                    span.parentSpanId shouldBe PARENT_ID
                    span.name shouldBe "GET /v1/orders/{id}"
                    span.attributes.get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE) shouldBe 200L
                }
            }

            test("受信した X-Correlation-Id を使い、MDC・span の属性・レスポンスのヘッダに入れる。スレッドを変えても MDC が保たれる") {
                TestTelemetry().use { telemetry ->
                    val seen = mutableListOf<Seen>()
                    var echoed: String? = null
                    testApplication {
                        observedApp(telemetry, seen)
                        echoed =
                            client
                                .get(
                                    "/v1/orders/42",
                                ) { header(CorrelationHeaders.X_CORRELATION_ID, "order-abc-123") }
                                .headers[CorrelationHeaders.X_CORRELATION_ID]
                    }
                    val span = telemetry.spans.single(SpanKind.SERVER, "/v1/orders/{id}")
                    val expected =
                        mapOf(
                            LogKeys.TRACE_ID to span.traceId,
                            LogKeys.SPAN_ID to span.spanId,
                            LogKeys.CORRELATION_ID to "order-abc-123",
                            LogKeys.INTEGRATION_ID to "INT-TEST-001",
                        )

                    echoed shouldBe "order-abc-123"
                    span.attributes.get(CORRELATION) shouldBe "order-abc-123"
                    seen.single().mdc shouldBe expected
                    seen.single().mdcOnIo shouldBe expected
                    // 処理の後はテストのスレッドに MDC が残らない
                    mdcSnapshot().values.toSet() shouldBe setOf(null)
                }
            }

            test("X-Correlation-Id がなければ入口で採番し、traceparent がなければ新しいトレースを始める") {
                TestTelemetry().use { telemetry ->
                    var echoed: String? = null
                    testApplication {
                        observedApp(telemetry)
                        echoed = client.get("/v1/orders/1").headers[CorrelationHeaders.X_CORRELATION_ID]
                    }
                    val span = telemetry.spans.single(SpanKind.SERVER, "/v1/orders/{id}")

                    echoed.shouldNotBeNull() shouldMatch Regex("^[0-9a-f-]{36}$")
                    span.attributes.get(CORRELATION) shouldBe echoed
                    span.parentSpanId shouldBe "0000000000000000"
                    // 起点のトレースは random フラグを立てる(OTel SDK 1.66。W3C Level 2 §3.2.2.5.2)
                    span.spanContext.traceFlags.asHex() shouldBe "03"
                }
            }

            test("不正な X-Correlation-Id と traceparent は捨て、受信した値をログにもヘッダにも出さない(件数はメトリクス)") {
                TestTelemetry().use { telemetry ->
                    val bad = "evil value <script>"
                    var echoed: String? = null
                    testApplication {
                        observedApp(telemetry)
                        echoed =
                            client
                                .get("/v1/orders/1") {
                                    header(CorrelationHeaders.X_CORRELATION_ID, "evil-é")
                                    header("traceparent", "00-$TRACE_ID-zz-01")
                                    header("X-Other", bad)
                                }.headers[CorrelationHeaders.X_CORRELATION_ID]
                    }
                    val span = telemetry.spans.single(SpanKind.SERVER, "/v1/orders/{id}")

                    echoed shouldNotBe "evil-é"
                    CorrelationId.parse(echoed.shouldNotBeNull()).ok().value shouldBe echoed
                    span.traceId shouldNotBe TRACE_ID
                    val bodies = telemetry.logs.map { it.bodyValue?.asString().orEmpty() }
                    // 不正な値の件数はメトリクスで数える(ログは DEBUG。FailureCasesSpec)
                    telemetry
                        .metrics()
                        .single { it.name == "eia.http.server.correlation_id.invalid" }
                        .longSumData.points
                        .single()
                        .value shouldBe 1L
                    bodies.none { it.contains("evil") } shouldBe true
                }
            }

            test("5xx と例外は ERROR にし、例外のメッセージは span に入れない。4xx はエラーにしない") {
                TestTelemetry().use { telemetry ->
                    testApplication {
                        observedApp(telemetry)
                        client.get("/v1/fail").status shouldBe HttpStatusCode.InternalServerError
                        client.get("/v1/missing").status shouldBe HttpStatusCode.NotFound
                    }
                    val failed = telemetry.spans.single(SpanKind.SERVER, "/v1/fail")
                    val missing = telemetry.spans.single(SpanKind.SERVER, "/v1/missing")

                    failed.status.statusCode shouldBe StatusCode.ERROR
                    failed.attributes.get(AttributeKey.stringKey("error.type")) shouldBe "java.lang.IllegalStateException"
                    failed.events.shouldBeEmpty() // 例外のイベント(recordException)を入れない
                    missing.status.statusCode shouldBe StatusCode.UNSET
                    // 未処理の例外は Correlation ID と trace_id の付いたログに残し、メッセージの秘密情報は伏せる
                    val error =
                        telemetry.logs.single {
                            it.bodyValue
                                ?.asString()
                                .orEmpty()
                                .startsWith("未処理の例外")
                        }
                    error.spanContext.traceId shouldBe failed.traceId
                    error.attributes.get(CORRELATION) shouldBe failed.attributes.get(CORRELATION)
                    error.attributes.get(AttributeKey.stringKey("exception.message")) shouldBe "失敗 password=***"
                    telemetry.logs.none { log ->
                        log.attributes
                            .asMap()
                            .values
                            .any { it.toString().contains("hunter2") }
                    } shouldBe true
                }
            }

            test("RED メトリクスは route テンプレート・メソッド・ステータス・integration_id で記録し、生のパスを入れない") {
                TestTelemetry().use { telemetry ->
                    testApplication {
                        observedApp(telemetry)
                        client.get("/v1/orders/1")
                        client.get("/v1/orders/2")
                        client.get("/v1/fail")
                    }
                    val histogram = telemetry.metrics().single { it.name == "http.server.request.duration" }
                    val points = histogram.histogramData.points

                    histogram.unit shouldBe "s"
                    points.map { it.attributes.get(HttpAttributes.HTTP_ROUTE) to it.count } shouldContainExactlyInAnyOrder
                        listOf("/v1/orders/{id}" to 2L, "/v1/fail" to 1L)
                    points.all { it.attributes.get(AttributeKey.stringKey(LogKeys.INTEGRATION_ID)) == "INT-TEST-001" } shouldBe true
                    points
                        .flatMap { p ->
                            p.attributes
                                .asMap()
                                .values
                                .map(Any::toString)
                        }.none { it.contains("/v1/orders/1") } shouldBe
                        true
                    points.single { it.attributes.get(HttpAttributes.HTTP_ROUTE) == "/v1/fail" }.attributes.get(
                        AttributeKey.stringKey("error.type"),
                    ) shouldBe "java.lang.IllegalStateException"
                }
            }
        }

        context("Client プラグイン") {
            test("サーバの処理から呼ぶと、traceparent と X-Correlation-Id が次のサービスへつながる") {
                TestTelemetry().use { telemetry ->
                    val seen = mutableListOf<Seen>()
                    testApplication {
                        val downstream = createClient { install(ClientObservability) { runtime = telemetry.runtime } }
                        observedApp(telemetry, seen) { downstream }
                        client.get("/v1/relay") { header(CorrelationHeaders.X_CORRELATION_ID, "relay-001") }
                    }
                    val relay = telemetry.spans.single(SpanKind.SERVER, "/v1/relay")
                    val clientSpan = telemetry.spans.single { it.kind == SpanKind.CLIENT }
                    val downstreamSpan = telemetry.spans.single(SpanKind.SERVER, "/v1/orders/{id}")

                    clientSpan.traceId shouldBe relay.traceId
                    clientSpan.parentSpanId shouldBe relay.spanId
                    downstreamSpan.traceId shouldBe relay.traceId
                    downstreamSpan.parentSpanId shouldBe clientSpan.spanId
                    clientSpan.attributes.get(CORRELATION) shouldBe "relay-001"
                    seen.single().mdc[LogKeys.CORRELATION_ID] shouldBe "relay-001"
                    telemetry
                        .metrics()
                        .single { it.name == "http.client.request.duration" }
                        .histogramData.points
                        .single()
                        .count shouldBe 1L
                }
            }

            test("コルーチンの ObservabilityContext を親にし、呼び出し側が付けた X-Correlation-Id は上書きしない") {
                TestTelemetry().use { telemetry ->
                    val seen = mutableListOf<Seen>()
                    testApplication {
                        observedApp(telemetry, seen)
                        val http = createClient { install(ClientObservability) { runtime = telemetry.runtime } }
                        val context = ObservabilityContext(CorrelationId.parse("from-context").ok())
                        withContext(context) { http.get("/v1/orders/1") }
                        withContext(context) { http.get("/v1/orders/2") { header(CorrelationHeaders.X_CORRELATION_ID, "explicit") } }
                    }

                    seen.map { it.mdc[LogKeys.CORRELATION_ID] } shouldBe listOf("from-context", "explicit")
                }
            }
        }
    })
