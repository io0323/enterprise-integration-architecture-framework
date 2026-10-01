@file:Suppress("MagicNumber") // テストデータの金額・件数

package io.eia.order.adapters.inbound.rest

import io.eia.order.adapters.out.persistence.ExposedOrderRepository
import io.eia.order.adapters.out.persistence.ExposedTransactionBoundary
import io.eia.order.adapters.out.persistence.ExposedTransactionRunner
import io.eia.order.adapters.out.persistence.OrderDatabase
import io.eia.order.adapters.out.persistence.OrderDatabaseEnvironment
import io.eia.order.adapters.out.persistence.PostgresIdempotencyStore
import io.eia.order.adapters.out.persistence.UuidV7OrderIdGenerator
import io.eia.order.application.usecase.GetOrderService
import io.eia.order.application.usecase.PlaceOrderService
import io.eia.platform.api.idempotency.IDEMPOTENCY_KEY_HEADER
import io.eia.platform.api.idempotency.IDEMPOTENT_REPLAYED_HEADER
import io.eia.platform.api.idempotency.IdempotencyHandler
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.security.ktor.eiaJwt
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
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
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Clock

private val RUNTIME =
    Observability.init(
        ObservabilityConfig.of("order-service-it").shouldBeInstanceOf<Result.Ok<ObservabilityConfig>>().value,
        TelemetrySinks(),
        installLogAppender = false,
    )

private const val BODY =
    """
    {"customerId":"cust-1",
     "lines":[{"productId":"prod-1","sku":"SKU-1","quantity":3,"unitPrice":{"amount":"10.50","currency":"USD"}},
              {"productId":"prod-2","sku":"SKU-2","quantity":1,"unitPrice":{"amount":"0.99","currency":"USD"}}],
     "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"千代田区","line1":"千代田 1-1"},
     "futureField":"未知の項目は無視する"}
    """

/** order-service の REST を、実際の PostgreSQL・JWT の検証・Problem Details・冪等の処理で組み立てる(app の配線と同じ順序)。 */
private fun ApplicationTestBuilder.orderService(
    db: OrderDatabase,
    idp: TestIdp,
) {
    val repository = ExposedOrderRepository(db.database)
    val api =
        OrderApi(
            placeOrder = PlaceOrderService(repository, ExposedTransactionRunner(db.database), UuidV7OrderIdGenerator(), Clock.System),
            getOrder = GetOrderService(repository),
            idempotency = IdempotencyHandler(PostgresIdempotencyStore(db.database)),
            transaction = ExposedTransactionBoundary(db.database),
        )
    application {
        install(ServerObservability) { runtime = RUNTIME }
        installProblemDetails { mapper = OrderProblems::mapper }
        install(Authentication) {
            eiaJwt {
                verifier = idp.verifier
                realm = "eiaf"
            }
        }
        routing { authenticate { orderRoutes(api) } }
    }
}

private suspend fun ApplicationTestBuilder.place(
    token: String?,
    key: String? = "key-1",
    body: String = BODY,
): HttpResponse =
    client.post("/v1/orders") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        key?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend fun HttpResponse.problemType(): String {
    val body = bodyAsText()
    ContractSchemas.violations("Problem", body).shouldBeEmpty()
    headers[HttpHeaders.ContentType] shouldBe "application/problem+json"
    return Regex(""""type":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: error("type がありません: $body")
}

class OrderRoutesIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment()
        val idp = TestIdp()
        beforeSpec { environment.start() }
        afterSpec {
            idp.close()
            environment.close()
        }

        context("POST /v1/orders") {
            test("201: 契約の Order の形で返し、Location は相対参照 orders/{id}。GET で同じ注文を読める") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val created = place(idp.token())

                    created.status shouldBe HttpStatusCode.Created
                    created.headers[HttpHeaders.ContentType] shouldBe "application/json"
                    val body = created.bodyAsText()
                    ContractSchemas.violations("Order", body).shouldBeEmpty()
                    body shouldContain """"totalAmount":{"amount":"32.49","currency":"USD"}"""
                    val location = created.headers[HttpHeaders.Location] ?: error("Location がありません")
                    location shouldStartWith "orders/"

                    val read = client.get("/v1/$location") { header(HttpHeaders.Authorization, "Bearer ${idp.token()}") }
                    read.status shouldBe HttpStatusCode.OK
                    ContractSchemas.violations("Order", read.bodyAsText()).shouldBeEmpty()
                    read.bodyAsText() shouldBe body
                }
            }

            test("同じ Idempotency-Key の再送は、同じ状態コード・Location・本文と Idempotent-Replayed: true を返す(注文は 1 件)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val first = place(idp.token())
                    val replay = place(idp.token())

                    replay.status shouldBe first.status
                    replay.headers[HttpHeaders.Location] shouldBe first.headers[HttpHeaders.Location]
                    replay.bodyAsText() shouldBe first.bodyAsText()
                    replay.headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe "true"
                    first.headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe null
                    replay.headers[CorrelationHeaders.X_CORRELATION_ID] shouldNotBe first.headers[CorrelationHeaders.X_CORRELATION_ID]
                    db.count("SELECT count(*) FROM orders") shouldBe 1
                }
            }

            test("同じキーで内容の違う要求は 422 idempotency-key-reused。キーはクライアントごとに独立する") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    place(idp.token())
                    val reused = place(idp.token(), body = BODY.replace("\"quantity\":3", "\"quantity\":4"))
                    reused.status shouldBe HttpStatusCode.UnprocessableEntity
                    reused.problemType() shouldBe "https://eiaf.example/problems/idempotency-key-reused"

                    place(idp.token(clientId = "client-b")).status shouldBe HttpStatusCode.Created
                    db.count("SELECT count(*) FROM orders") shouldBe 2
                }
            }

            test("Idempotency-Key がなければ 400 idempotency-key-missing(処理しない)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val response = place(idp.token(), key = null)
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.problemType() shouldBe "https://eiaf.example/problems/idempotency-key-missing"
                    db.count("SELECT count(*) FROM orders") shouldBe 0
                }
            }

            test("本文の構造が不正(JSON でない・必須の項目がない)なら 400 bad-request") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    listOf("not json", """{"customerId":"cust-1"}""").forEachIndexed { index, body ->
                        val response = place(idp.token(), key = "bad-$index", body = body)
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.problemType() shouldBe "https://eiaf.example/problems/bad-request"
                    }
                }
            }

            test("値域・業務整合の違反は 422 validation-failed で、errors に契約の項目のパスを入れる") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val invalid =
                        BODY
                            .replace("\"quantity\":3", "\"quantity\":0")
                            .replace("\"amount\":\"0.99\"", "\"amount\":\"0.999\"")
                    val response = place(idp.token(), body = invalid)

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    response.problemType() shouldBe "https://eiaf.example/problems/validation-failed"
                    response.bodyAsText() shouldContain """"field":"lines[1].unitPrice.amount""""
                    // 金額の違反があるときは、金額の解析の時点で返す(domain の検証の前)
                    db.count("SELECT count(*) FROM orders") shouldBe 0

                    val quantityOnly = place(idp.token(), key = "key-2", body = BODY.replace("\"quantity\":3", "\"quantity\":0"))
                    quantityOnly.bodyAsText() shouldContain """"field":"lines[0].quantity""""
                }
            }
        }

        context("認証と認可") {
            test("トークンがなければ 401、書き込みのスコープがなければ 403(処理しない)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val anonymous = place(null)
                    anonymous.status shouldBe HttpStatusCode.Unauthorized
                    anonymous.problemType() shouldBe "https://eiaf.example/problems/unauthorized"

                    val readOnly = place(idp.token(scopes = SCOPE_READ))
                    readOnly.status shouldBe HttpStatusCode.Forbidden
                    readOnly.problemType() shouldBe "https://eiaf.example/problems/forbidden"
                    db.count("SELECT count(*) FROM orders") shouldBe 0
                }
            }

            test("呼び出し元のクライアント(azp も client_id も)がないトークンは 401 invalid_token(Idempotency-Key の範囲を決められない)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val response = place(idp.token(clientId = null))
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer realm="eiaf", error="invalid_token""""
                    response.problemType() shouldBe "https://eiaf.example/problems/unauthorized"
                    db.count("SELECT count(*) FROM idempotency_record") shouldBe 0
                }
            }
        }

        context("GET /v1/orders/{orderId}") {
            test("ない注文と、形式が不正な ID(存在しえない)は 404 not-found") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    listOf("ord-none", "x".repeat(65)).forEach { id ->
                        val response = client.get("/v1/orders/$id") { header(HttpHeaders.Authorization, "Bearer ${idp.token()}") }
                        response.status shouldBe HttpStatusCode.NotFound
                        response.problemType() shouldBe "https://eiaf.example/problems/not-found"
                    }
                }
            }

            test("読み取りのスコープがなければ 403") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    client
                        .get("/v1/orders/ord-1") { header(HttpHeaders.Authorization, "Bearer ${idp.token(scopes = SCOPE_WRITE)}") }
                        .status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })
