package io.eia.platform.observability.ktor

import io.eia.platform.observability.TestTelemetry
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.context.LogKeys
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.context.withSpan
import io.eia.platform.observability.ktor.client.ClientObservability
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.observability.ok
import io.eia.shared.kernel.CorrelationId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.semconv.HttpAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException

private val ERROR_TYPE = AttributeKey.stringKey("error.type")
private val CORRELATION = AttributeKey.stringKey(LogKeys.CORRELATION_ID)
private val logger = LoggerFactory.getLogger("io.eia.platform.observability.ktor.FailureCasesSpec")

private fun mockClient(
    telemetry: TestTelemetry,
    sent: MutableList<Headers> = mutableListOf(),
    respond: suspend () -> HttpStatusCode,
): HttpClient =
    HttpClient(
        MockEngine { request ->
            sent += request.headers
            respond("", respond())
        },
    ) { install(ClientObservability) { runtime = telemetry.runtime } }

class FailureCasesSpec :
    FunSpec({
        context("Client プラグインの失敗系") {
            test("接続の失敗は error.type に例外の型を入れ、ERROR にして再送出する") {
                TestTelemetry().use { telemetry ->
                    val client = mockClient(telemetry) { throw IOException("connection refused to secret-host") }

                    shouldThrow<IOException> { client.get("http://inventory:8080/v1/stock") }
                    val span = telemetry.spans.single()

                    span.kind shouldBe SpanKind.CLIENT
                    span.status.statusCode shouldBe StatusCode.ERROR
                    span.attributes.get(ERROR_TYPE) shouldBe "java.io.IOException"
                    span.attributes.get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE) shouldBe null
                    span.events.shouldBeEmpty() // 例外のメッセージを span に入れない
                }
            }

            test("4xx はクライアントではエラー(semconv)") {
                TestTelemetry().use { telemetry ->
                    mockClient(telemetry) { HttpStatusCode.Conflict }.get("http://inventory:8080/v1/stock")
                    val span = telemetry.spans.single()

                    span.attributes.get(ERROR_TYPE) shouldBe "409"
                    span.status.statusCode shouldBe StatusCode.ERROR
                }
            }

            test("呼び出し側のキャンセルは、ステータスもエラーも記録しない") {
                TestTelemetry().use { telemetry ->
                    val client = mockClient(telemetry) { throw CancellationException("caller gave up") }

                    shouldThrow<CancellationException> { client.get("http://inventory:8080/v1/stock") }
                    val span = telemetry.spans.single()
                    val point =
                        telemetry
                            .metrics()
                            .single { it.name == "http.client.request.duration" }
                            .histogramData.points
                            .single()

                    span.status.statusCode shouldBe StatusCode.UNSET
                    span.attributes.get(ERROR_TYPE) shouldBe null
                    point.attributes.get(ERROR_TYPE) shouldBe null
                    point.attributes.get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE) shouldBe null
                }
            }

            test("タイムアウト(withTimeout の期限切れ)は error.type=timeout でエラーに数える") {
                TestTelemetry().use { telemetry ->
                    val client =
                        mockClient(telemetry) {
                            kotlinx.coroutines.delay(10_000)
                            HttpStatusCode.OK
                        }

                    shouldThrow<kotlinx.coroutines.TimeoutCancellationException> {
                        kotlinx.coroutines.withTimeout(50) { client.get("http://inventory:8080/v1/stock") }
                    }
                    val span = telemetry.spans.single()

                    span.status.statusCode shouldBe StatusCode.ERROR
                    span.attributes.get(ERROR_TYPE) shouldBe "timeout"
                }
            }

            test("明示的な X-Correlation-Id は、正しければ送って span にも入れ、不正ならコンテキストの値に置き換える") {
                TestTelemetry().use { telemetry ->
                    val sent = mutableListOf<Headers>()
                    val client = mockClient(telemetry, sent) { HttpStatusCode.OK }
                    withContext(ObservabilityContext(CorrelationId.parse("from-context").ok())) {
                        client.get("http://a/1") { header(CorrelationHeaders.X_CORRELATION_ID, "explicit-1") }
                        client.get("http://a/2") { header(CorrelationHeaders.X_CORRELATION_ID, "bad value!") }
                    }
                    client.get("http://a/3") { header(CorrelationHeaders.X_CORRELATION_ID, "bad value!") }

                    sent.map { it.getAll(CorrelationHeaders.X_CORRELATION_ID) } shouldBe
                        listOf(listOf("explicit-1"), listOf("from-context"), null)
                    telemetry.spans.map { it.attributes.get(CORRELATION) } shouldBe listOf("explicit-1", "from-context", null)
                }
            }
        }

        context("Server プラグインの失敗系") {
            test("ハンドラのキャンセルは 500 にもエラーにも数えない") {
                TestTelemetry().use { telemetry ->
                    testApplication {
                        application {
                            install(ServerObservability) { runtime = telemetry.runtime }
                            routing { get("/v1/slow") { throw CancellationException("client disconnected") } }
                        }
                        runCatchingCancellation { client.get("/v1/slow") }
                    }
                    val span = telemetry.spans.single { it.kind == SpanKind.SERVER }
                    val point =
                        telemetry
                            .metrics()
                            .single { it.name == "http.server.request.duration" }
                            .histogramData.points
                            .single()

                    span.status.statusCode shouldBe StatusCode.UNSET
                    span.attributes.get(ERROR_TYPE) shouldBe null
                    point.attributes.get(ERROR_TYPE) shouldBe null
                    point.attributes.get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE) shouldBe null
                    telemetry.logs.none {
                        it.bodyValue
                            ?.asString()
                            .orEmpty()
                            .startsWith("未処理の例外")
                    } shouldBe true
                }
            }

            test("不正な X-Correlation-Id は WARN を出さず、件数をメトリクスで数える(ログの増幅を防ぐ)") {
                TestTelemetry().use { telemetry ->
                    testApplication {
                        application {
                            install(ServerObservability) {
                                runtime = telemetry.runtime
                                integrationId = "INT-TEST-001"
                            }
                            routing { get("/v1/x") { call.respondText("ok") } }
                        }
                        repeat(3) { client.get("/v1/x") { header(CorrelationHeaders.X_CORRELATION_ID, "bad value!") } }
                        client.get("/v1/x") { header(CorrelationHeaders.X_CORRELATION_ID, "good-1") }
                    }
                    val counter = telemetry.metrics().single { it.name == "eia.http.server.correlation_id.invalid" }

                    counter.longSumData.points
                        .single()
                        .value shouldBe 3L
                    telemetry.logs.none {
                        it.bodyValue
                            ?.asString()
                            .orEmpty()
                            .contains("不正")
                    } shouldBe true
                }
            }

            test("route テンプレートは入れ子のルートと末尾のスラッシュでもパスだけにする") {
                TestTelemetry().use { telemetry ->
                    testApplication {
                        application {
                            install(ServerObservability) { runtime = telemetry.runtime }
                            routing {
                                route("/v1") {
                                    route("/orders") { get("{id}/lines/{line}") { call.respondText("ok") } }
                                }
                                get("/v1/items/") { call.respondText("ok") }
                                get("/") { call.respondText("ok") }
                            }
                        }
                        client.get("/v1/orders/7/lines/2")
                        client.get("/v1/items/")
                        client.get("/")
                    }

                    telemetry.spans.map { it.attributes.get(HttpAttributes.HTTP_ROUTE) } shouldContainExactlyInAnyOrder
                        listOf("/v1/orders/{id}/lines/{line}", "/v1/items", "/")
                }
            }
        }

        context("withSpan") {
            test("サーバの処理の中で子の span を現在にし、ログと送信の親をその span にする") {
                TestTelemetry().use { telemetry ->
                    val downstream = mockClient(telemetry) { HttpStatusCode.OK }
                    testApplication {
                        application {
                            install(ServerObservability) { runtime = telemetry.runtime }
                            routing {
                                get("/v1/orders/{id}") {
                                    telemetry.runtime.withSpan("reserve-stock") {
                                        logger.info("在庫を引き当てます")
                                        downstream.get("http://inventory:8080/v1/stock")
                                    }
                                    call.respondText("ok")
                                }
                            }
                        }
                        client.get("/v1/orders/1") { header(CorrelationHeaders.X_CORRELATION_ID, "with-span-1") }
                    }
                    val server = telemetry.spans.single { it.kind == SpanKind.SERVER }
                    val child = telemetry.spans.single { it.name == "reserve-stock" }
                    val outgoing = telemetry.spans.single { it.kind == SpanKind.CLIENT }
                    val log = telemetry.logs.single { it.bodyValue?.asString() == "在庫を引き当てます" }

                    child.parentSpanId shouldBe server.spanId
                    child.attributes.get(CORRELATION) shouldBe "with-span-1"
                    outgoing.parentSpanId shouldBe child.spanId
                    outgoing.attributes.get(CORRELATION) shouldBe "with-span-1"
                    log.spanContext.spanId shouldBe child.spanId
                    // 現在の span からフラグも引き継ぐ(sampled と random)
                    log.spanContext.traceFlags.asHex() shouldBe "03"
                }
            }

            test("例外は error.type を付けて再送出し、ObservabilityContext がなければ Correlation ID を採番する") {
                TestTelemetry().use { telemetry ->
                    shouldThrow<IllegalStateException> {
                        telemetry.runtime.withSpan("job") { error("failed password=hunter2") }
                    }
                    val seen = telemetry.runtime.withSpan("job2") { kotlinx.coroutines.currentCoroutineContext()[ObservabilityContext] }
                    val failed = telemetry.spans.single { it.name == "job" }

                    failed.status.statusCode shouldBe StatusCode.ERROR
                    failed.attributes.get(ERROR_TYPE) shouldBe "java.lang.IllegalStateException"
                    failed.events.shouldBeEmpty()
                    val generated = CorrelationId.parse(seen?.correlationId?.value.orEmpty()).ok()
                    // 採番した Correlation ID を span の属性にも残す
                    telemetry.spans
                        .single { it.name == "job2" }
                        .attributes
                        .get(CORRELATION) shouldBe generated.value
                }
            }
        }
    })

/** testApplication のクライアントは、サーバ側のキャンセルを例外として受け取ることがある。どちらでもよいので無視する。 */
private suspend fun runCatchingCancellation(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: CancellationException) {
        // 期待どおり
    }
}
