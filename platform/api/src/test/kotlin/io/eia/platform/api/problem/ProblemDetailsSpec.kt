package io.eia.platform.api.problem

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private val RUNTIME_CONFIG =
    when (val config = ObservabilityConfig.of("api-test")) {
        is Result.Ok -> config.value
        is Result.Err -> fail("設定が不正です: ${config.error}")
    }

private val RUNTIME = Observability.init(RUNTIME_CONFIG, TelemetrySinks(), installLogAppender = false)

/** 秘密情報を含む例外のメッセージ。応答に出てはいけない。 */
private const val SECRET = "password=hunter2 jdbc:postgresql://db.internal:5432/orders"

private class OutOfStock : DomainError.NonRetryable {
    override val code: String get() = "out_of_stock"
    override val message: String get() = "sku-1 の在庫が足りません"
}

private fun ApplicationTestBuilder.app(
    observability: Boolean = true,
    config: ProblemDetailsConfig.() -> Unit = {},
    routes: Routing.() -> Unit,
) {
    application {
        if (observability) install(ServerObservability) { runtime = RUNTIME }
        installProblemDetails(config)
        routing(routes)
    }
}

private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

/** 期待する本文(JSON の文字列)を、項目の順序によらず比べられる形にする。 */
private fun expected(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class ProblemDetailsSpec :
    FunSpec({
        context("DomainError の写し方") {
            test("ValidationError は 422 validation-failed で、errors に項目と理由を入れる。correlationId は応答のヘッダと同じ") {
                testApplication {
                    app {
                        get("/orders") {
                            call.respondError(
                                ValidationError(
                                    listOf(FieldViolation("lines[0].quantity", "1 以上です"), FieldViolation("customerId", "必須です")),
                                ),
                            )
                        }
                    }
                    val response = client.get("/orders")

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.headers[HttpHeaders.ContentType] shouldBe "application/problem+json"
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    val correlationId = response.headers[CorrelationHeaders.X_CORRELATION_ID]
                    response.json() shouldBe
                        expected(
                            """
                            {"type":"https://eiaf.example/problems/validation-failed","title":"Validation failed","status":422,
                             "detail":"The request contains invalid values. See errors.","correlationId":"$correlationId",
                             "errors":[{"field":"lines[0].quantity","message":"1 以上です"},{"field":"customerId","message":"必須です"}]}
                            """,
                        )
                }
            }

            test("受信した X-Correlation-Id を correlationId に入れる") {
                testApplication {
                    app { get("/orders/{id}") { call.respondError(NotFoundError("order", "ord-1")) } }
                    val response = client.get("/orders/ord-1") { header(CorrelationHeaders.X_CORRELATION_ID, "corr-12345") }

                    response.status shouldBe HttpStatusCode.NotFound
                    response.json()["correlationId"].toString() shouldBe "\"corr-12345\""
                }
            }

            test("DomainError.message は detail に使わない(業務の値や参照キーを含みうるため)") {
                testApplication {
                    app { get("/orders/{id}") { call.respondError(NotFoundError("order", "ord-secret-1")) } }
                    val body = client.get("/orders/x").bodyAsText()

                    body shouldNotContain "ord-secret-1"
                    body shouldNotContain "見つかりません"
                }
            }

            test("ConflictError は 409 conflict") {
                testApplication {
                    app { post("/orders") { call.respondError(ConflictError("在庫が足りません")) } }
                    val response = client.post("/orders")

                    response.status shouldBe HttpStatusCode.Conflict
                    response.json()["type"].toString() shouldBe "\"https://eiaf.example/problems/conflict\""
                }
            }

            test("Retryable は 503 service-unavailable で、retryAfter を秒に切り上げて Retry-After に入れる") {
                testApplication {
                    app { get("/orders") { call.respondError(UnavailableError("在庫サービスに接続できません", 1_500.milliseconds)) } }
                    val response = client.get("/orders")

                    response.status shouldBe HttpStatusCode.ServiceUnavailable
                    response.headers[HttpHeaders.RetryAfter] shouldBe "2"
                    response.json()["type"].toString() shouldBe "\"https://eiaf.example/problems/service-unavailable\""
                }
            }

            test("写し方の決まっていない NonRetryable は 500 internal-error。mapper で業務エラーの種類を決められる") {
                testApplication {
                    app { get("/orders") { call.respondError(OutOfStock()) } }
                    client.get("/orders").status shouldBe HttpStatusCode.InternalServerError
                }
                testApplication {
                    app(config = { mapper = { error -> if (error is OutOfStock) Problem(ProblemType.CONFLICT) else null } }) {
                        get("/orders") { call.respondError(OutOfStock()) }
                        get("/missing") { call.respondError(NotFoundError("order", "ord-1")) }
                    }
                    client.get("/orders").status shouldBe HttpStatusCode.Conflict
                    // mapper が null を返した種類は既定で写す
                    client.get("/missing").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        context("例外") {
            test("想定外の例外は 500 internal-error。例外のメッセージとスタックトレースを応答に出さない") {
                testApplication {
                    app { get("/orders") { throw IllegalStateException(SECRET) } }
                    val response = client.get("/orders")
                    val body = response.bodyAsText()

                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.headers[HttpHeaders.ContentType] shouldBe "application/problem+json"
                    Json.parseToJsonElement(body).jsonObject shouldBe
                        expected(
                            """
                            {"type":"https://eiaf.example/problems/internal-error","title":"Internal error","status":500,
                             "detail":"An unexpected error occurred.","correlationId":"${response.headers[CorrelationHeaders.X_CORRELATION_ID]}"}
                            """,
                        )
                    body shouldNotContain "hunter2"
                    body shouldNotContain "IllegalStateException"
                    body shouldNotContain "at io."
                }
            }

            test("本文を読めない(型に変換できない)ときは 400 bad-request") {
                testApplication {
                    app { post("/orders") { call.respond(call.receive<Int>()) } }
                    val response = client.post("/orders") { setBody("not a number") }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.json()["type"].toString() shouldBe "\"https://eiaf.example/problems/bad-request\""
                }
            }

            test("BadRequestException のメッセージ(パーサのメッセージ。本文の断片を含みうる)は応答に出さない") {
                testApplication {
                    app { post("/orders") { throw BadRequestException("Unexpected JSON token near '$SECRET'") } }
                    val response = client.post("/orders")

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldNotContain "hunter2"
                }
            }

            test("タイムアウト(キャンセル)は扱わず、Ktor の既定どおり 504 にする") {
                testApplication {
                    app {
                        get("/slow") {
                            withTimeout(10.milliseconds) { delay(1_000) }
                        }
                    }
                    client.get("/slow").status shouldBe HttpStatusCode.GatewayTimeout
                }
            }
        }

        context("ルーティング") {
            test("どのルートにも当たらなければ 404 not-found") {
                testApplication {
                    app { get("/orders") { call.respond("ok") } }
                    val response = client.get("/unknown")

                    response.status shouldBe HttpStatusCode.NotFound
                    response.headers[HttpHeaders.ContentType] shouldBe "application/problem+json"
                    response.json()["type"].toString() shouldBe "\"https://eiaf.example/problems/not-found\""
                }
            }

            test("状態コードだけの 404 も Problem Details にする。respondProblem で返した 404 は上書きしない") {
                testApplication {
                    app {
                        get("/bare") { call.respond(HttpStatusCode.NotFound) }
                        get("/problem") { call.respondError(NotFoundError("order", "ord-1")) }
                    }
                    client.get("/bare").json()["type"].toString() shouldBe "\"https://eiaf.example/problems/not-found\""
                    val response = client.get("/problem")
                    response.status shouldBe HttpStatusCode.NotFound
                    response.json()["detail"].toString() shouldBe "\"The requested resource was not found.\""
                }
            }

            test("許可されていないメソッドは 405 about:blank") {
                testApplication {
                    app { get("/orders") { call.respond("ok") } }
                    val response = client.post("/orders")

                    response.status shouldBe HttpStatusCode.MethodNotAllowed
                    response.json() shouldBe
                        expected(
                            """
                            {"type":"about:blank","title":"Method Not Allowed","status":405,
                             "correlationId":"${response.headers[CorrelationHeaders.X_CORRELATION_ID]}"}
                            """,
                        )
                }
            }
        }

        context("ServerObservability との組み合わせ") {
            test("例外を写した応答の状態コードを RED メトリクスに記録し、未処理の例外のログは Correlation ID 付きで 1 回だけ") {
                val reader = InMemoryMetricReader.create()
                val runtime = Observability.init(RUNTIME_CONFIG, TelemetrySinks(metricReaders = listOf(reader)), installLogAppender = false)
                val logs = ListAppender<ILoggingEvent>().apply { start() }
                val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                root.addAppender(logs)
                try {
                    testApplication {
                        application {
                            install(ServerObservability) { this.runtime = runtime }
                            installProblemDetails()
                            routing {
                                post("/orders") { throw BadRequestException("bad") }
                                get("/orders") { throw IllegalStateException(SECRET) }
                            }
                        }
                        client.post("/orders").status shouldBe HttpStatusCode.BadRequest
                        val failed = client.get("/orders")
                        failed.status shouldBe HttpStatusCode.InternalServerError

                        val statuses =
                            reader
                                .collectAllMetrics()
                                .single { it.name == "http.server.request.duration" }
                                .histogramData.points
                                .map { it.attributes.get(AttributeKey.longKey("http.response.status_code")) }
                        statuses.sortedBy { it } shouldBe listOf(400L, 500L)

                        val unhandled = logs.list.filter { it.formattedMessage.startsWith("未処理の例外で 500") }
                        unhandled.size shouldBe 1
                        unhandled.single().mdcPropertyMap["correlation_id"] shouldBe failed.headers[CorrelationHeaders.X_CORRELATION_ID]
                    }
                } finally {
                    root.detachAppender(logs)
                    runtime.close()
                }
            }
        }

        test("ServerObservability がなければ、correlationId を付けない") {
            testApplication {
                app(observability = false) { get("/orders") { call.respondError(ConflictError("x")) } }
                client.get("/orders").json().containsKey("correlationId") shouldBe false
            }
        }

        context("ProblemType") {
            test("登録済みの種類の type は基底 URI の下で重複せず、title がある") {
                ProblemType.ALL.forEach {
                    it.uri.startsWith(ProblemType.BASE_URI) shouldBe true
                    it.title.isNotBlank() shouldBe true
                }
                ProblemType.ALL
                    .map { it.uri }
                    .toSet()
                    .size shouldBe ProblemType.ALL.size
            }

            test("登録済みの種類は INTEGRATION_STANDARDS §6 の表と一致する(type と status)") {
                val standards = File("../../docs/standards/INTEGRATION_STANDARDS.md").readText()
                val section = standards.substringAfter("## 6. エラー応答").substringBefore("\n## ")
                val documented =
                    Regex("""^\| `([a-z-]+)` \| (\d{3}) \|""", RegexOption.MULTILINE)
                        .findAll(section)
                        .map { ProblemType.BASE_URI + it.groupValues[1] to it.groupValues[2].toInt() }
                        .toSet()
                documented shouldBe ProblemType.ALL.map { it.uri to it.status }.toSet()
            }

            test("Retry-After は秒に切り上げ、負の値は 0 にする") {
                retryAfterSeconds(1.milliseconds) shouldBe "1"
                retryAfterSeconds(1_000.milliseconds) shouldBe "1"
                retryAfterSeconds(1_001.milliseconds) shouldBe "2"
                retryAfterSeconds((-5).milliseconds) shouldBe "0"
            }
        }
    })
