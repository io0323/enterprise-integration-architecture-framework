package io.eia.platform.api.idempotency

import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.api.problem.problemSnapshot
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private val RUNTIME =
    Observability.init(
        ObservabilityConfig.of("api-test").shouldBeInstanceOf<Result.Ok<ObservabilityConfig>>().value,
        TelemetrySinks(),
        installLogAppender = false,
    )

/** 注文を登録する API(`POST /v1/orders`)。本文に `"invalid"` を含めば 422、`"down"` を含めば 503 を返す。 */
private class OrderApi {
    val transaction = FakeTransaction()
    val store = FakeIdempotencyStore(transaction)
    val handler = IdempotencyHandler(store)
    val orders = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    fun install(builder: ApplicationTestBuilder) {
        builder.application {
            install(ServerObservability) { runtime = RUNTIME }
            installProblemDetails()
            routing {
                post("/v1/orders") {
                    call.respondIdempotently(handler, clientId = "client-a", transaction) { body ->
                        gate?.await()
                        val text = body.decodeToString()
                        when {
                            "invalid" in text -> {
                                call.problemSnapshot(Problem(ProblemType.VALIDATION_FAILED))
                            }

                            "down" in text -> {
                                call.problemSnapshot(Problem(ProblemType.SERVICE_UNAVAILABLE))
                            }

                            else -> {
                                val id = "ord-${orders.size + 1}"
                                transaction.write { orders += id }
                                HttpSnapshot(
                                    201,
                                    listOf(HttpHeaders.ContentType to "application/json", HttpHeaders.Location to "orders/$id"),
                                    """{"id":"$id"}""".toByteArray(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationTestBuilder.place(
    body: String = """{"sku":"A"}""",
    key: String? = "key-1",
): HttpResponse =
    client.post("/v1/orders") {
        key?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

class IdempotentCallSpec :
    FunSpec({
        test("同じキーの再送は、同じ状態コード・Location・本文を返し、Idempotent-Replayed: true を付ける。X-Correlation-Id はその要求のもの") {
            testApplication {
                val api = OrderApi().also { it.install(this) }
                val first = place()
                val replay = place("""{ "sku": "A" }""")

                first.status shouldBe HttpStatusCode.Created
                first.headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe null
                replay.status shouldBe HttpStatusCode.Created
                replay.headers[HttpHeaders.Location] shouldBe first.headers[HttpHeaders.Location]
                replay.headers[HttpHeaders.ContentType] shouldBe "application/json"
                replay.bodyAsText() shouldBe first.bodyAsText()
                replay.headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe "true"
                replay.headers[CorrelationHeaders.X_CORRELATION_ID] shouldNotBe first.headers[CorrelationHeaders.X_CORRELATION_ID]
                api.orders shouldBe listOf("ord-1")
            }
        }

        test("キーがない・形式が不正・複数ある(, で連結された値を含む)ときは、処理せずに 400 idempotency-key-missing") {
            testApplication {
                val api = OrderApi().also { it.install(this) }
                listOf(
                    place(key = null),
                    place(key = "bad key"),
                    client.post("/v1/orders") {
                        header(IDEMPOTENCY_KEY_HEADER, "k1")
                        header(IDEMPOTENCY_KEY_HEADER, "k2")
                    },
                    place(key = "k1,k2"),
                ).forEach { response ->
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldContain "https://eiaf.example/problems/idempotency-key-missing"
                }
                api.orders shouldBe emptyList()
            }
        }

        test("同じキーで内容の違う要求は 422 idempotency-key-reused") {
            testApplication {
                OrderApi().also { it.install(this) }
                place("""{"sku":"A"}""")
                val response = place("""{"sku":"B"}""")

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "https://eiaf.example/problems/idempotency-key-reused"
            }
        }

        test("処理中の同じキーは 409 idempotency-request-in-progress で、Retry-After はリースの残り時間") {
            testApplication {
                val api = OrderApi().also { it.install(this) }
                api.gate = CompletableDeferred()
                coroutineScope {
                    val first = async { place() }
                    while (api.store.size == 0) delay(10)
                    val second = place()

                    second.status shouldBe HttpStatusCode.Conflict
                    second.headers[HttpHeaders.RetryAfter] shouldBe "60"
                    second.bodyAsText() shouldContain "https://eiaf.example/problems/idempotency-request-in-progress"

                    api.gate?.complete(Unit)
                    first.await().status shouldBe HttpStatusCode.Created
                }
            }
        }

        test("4xx の Problem は保存して再送にも同じ本文を返す。503 は保存せず、同じキーで再試行できる") {
            testApplication {
                val api = OrderApi().also { it.install(this) }
                val invalid = place("""{"sku":"invalid"}""", key = "key-422")
                val replayed = place("""{"sku":"invalid"}""", key = "key-422")
                invalid.status shouldBe HttpStatusCode.UnprocessableEntity
                replayed.bodyAsText() shouldBe invalid.bodyAsText()
                replayed.headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe "true"
                replayed.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                place("""{"sku":"down"}""", key = "key-503").status shouldBe HttpStatusCode.ServiceUnavailable
                place("""{"sku":"down"}""", key = "key-503").headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe null
                api.store.size shouldBe 1
            }
        }
    })
