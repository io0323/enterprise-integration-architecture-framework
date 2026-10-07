@file:Suppress("MagicNumber") // テストデータの金額・件数

package io.eia.order.adapters.inbound.rest

import io.eia.order.adapters.out.audit.ExposedOrderAuditTrail
import io.eia.order.adapters.out.outbox.OrderCreatedV1
import io.eia.order.adapters.out.outbox.OrderEventSchemas
import io.eia.order.adapters.out.outbox.OutboxOrderEvents
import io.eia.order.adapters.out.persistence.ExposedOrderRepository
import io.eia.order.adapters.out.persistence.ExposedTransactionBoundary
import io.eia.order.adapters.out.persistence.ExposedTransactionRunner
import io.eia.order.adapters.out.persistence.OrderDatabase
import io.eia.order.adapters.out.persistence.OrderDatabaseEnvironment
import io.eia.order.adapters.out.persistence.PostgresIdempotencyStore
import io.eia.order.adapters.out.persistence.UuidV7OrderIdGenerator
import io.eia.order.application.usecase.GetOrderService
import io.eia.order.application.usecase.PlaceOrderService
import io.eia.platform.api.idempotency.CanonicalBody
import io.eia.platform.api.idempotency.IDEMPOTENCY_KEY_HEADER
import io.eia.platform.api.idempotency.IDEMPOTENT_REPLAYED_HEADER
import io.eia.platform.api.idempotency.IdempotencyHandler
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.security.ktor.eiaJwt
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

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

/**
 * ナノ秒の端数を持つ時計(Linux の `Clock.System` と同じ精度)。macOS の時計はマイクロ秒までなので、そのままでは
 * 「登録の応答の時刻と、保存して読み直した時刻(PostgreSQL はマイクロ秒)の食い違い」を再現できない(PR #60 の CI で発生)。
 */
private val NANOSECOND_CLOCK =
    object : Clock {
        override fun now(): Instant = Clock.System.now() + 505.nanoseconds
    }

private val EVENT_RUNTIME =
    Observability.init(
        ObservabilityConfig.of("order-routes-it").let {
            (it as Result.Ok).value
        },
        TelemetrySinks(),
        installLogAppender = false,
    )

/** 解決済みの OrderCreated の serializer(Schema Registry の代わりに MockEngine で contentId 1 を返す)。 */
private fun resolvedOrderCreated(): AvroEventSerializer<OrderCreatedV1> {
    val registry =
        HttpClient(
            MockEngine {
                respond(
                    """{"versions":[{"contentId":1,"state":"ENABLED"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
    val book =
        SchemaIdBook(
            OrderEventSchemas.subjects,
            ApicurioRegistryClient(SchemaRegistryConfig("http://registry.test/apis/registry/v3"), registry),
        )
    runBlocking { check(book.resolve() is Result.Ok) }
    return OrderEventSchemas.orderCreatedSerializer(book)
}

/** order-service の REST を、実際の PostgreSQL・JWT の検証・Problem Details・冪等の処理で組み立てる(app の配線と同じ順序)。 */
private fun ApplicationTestBuilder.orderService(
    db: OrderDatabase,
    idp: TestIdp,
) {
    val repository = ExposedOrderRepository(db.database)
    val api =
        OrderApi(
            placeOrder =
                PlaceOrderService(
                    repository,
                    ExposedTransactionRunner(db.database),
                    UuidV7OrderIdGenerator(),
                    NANOSECOND_CLOCK,
                    ExposedOrderAuditTrail(db.database, AuditLog()),
                    OutboxOrderEvents(db.database, Outbox(), OutboxEvents(EVENT_RUNTIME, "/sales/order-service"), resolvedOrderCreated()),
                ),
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

            test("受け付けを同じトランザクションで監査に記録する(azp・order.create・正規化した本文の SHA-256・Correlation ID・traceparent)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    val created = place(idp.token(clientId = "client-a"))
                    created.status shouldBe HttpStatusCode.Created
                    val orderId = created.headers[HttpHeaders.Location]!!.removePrefix("orders/")

                    val row =
                        db.superuser { c ->
                            c
                                .prepareStatement(
                                    "SELECT actor_type, actor_id, action, target_type, target_id, outcome, payload_sha256, " +
                                        "correlation_id, traceparent FROM audit.audit_log",
                                ).use { st ->
                                    st.executeQuery().use { rs ->
                                        rs.next() shouldBe true
                                        val values = (1..9).map { rs.getString(it) }
                                        rs.next() shouldBe false
                                        values
                                    }
                                }
                        }
                    row.take(6) shouldBe listOf("service", "client-a", "order.create", "order", orderId, "success")
                    row[6] shouldBe CanonicalBody.sha256Hex("application/json", BODY.toByteArray())
                    row[7] shouldBe created.headers[CorrelationHeaders.X_CORRELATION_ID]
                    row[8].shouldNotBeNull() shouldStartWith "00-"
                }
            }

            test("監査の本文の SHA-256 は、空白とキーの順序が違うだけの本文なら同じ値(冪等の指紋と同じ正規化)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    // 同じ内容で、キーの順序を逆にし、前後に空白を足した本文
                    val entries =
                        Json
                            .parseToJsonElement(BODY)
                            .jsonObject.entries
                            .reversed()
                    val reordered = JsonObject(entries.associate { it.toPair() }).toString()
                    place(idp.token(), key = "key-a").status shouldBe HttpStatusCode.Created
                    place(idp.token(), key = "key-b", body = "  $reordered  ").status shouldBe HttpStatusCode.Created
                    db.count("SELECT count(DISTINCT payload_sha256) FROM audit.audit_log") shouldBe 1
                    db.count("SELECT count(*) FROM audit.audit_log") shouldBe 2
                }
            }

            test("再送(Idempotent-Replayed)・検証の違反・本文の構造の不正は、監査に記録しない(業務の更新がない)") {
                val db = environment.newDatabase()
                testApplication {
                    orderService(db, idp)
                    place(idp.token()).status shouldBe HttpStatusCode.Created
                    place(idp.token()).headers[IDEMPOTENT_REPLAYED_HEADER] shouldBe "true"
                    place(idp.token(), key = "key-bad", body = "{").status shouldBe HttpStatusCode.BadRequest
                    place(idp.token(), key = "key-invalid", body = BODY.replace("\"quantity\":3", "\"quantity\":0")).status shouldBe
                        HttpStatusCode.UnprocessableEntity
                    db.count("SELECT count(*) FROM audit.audit_log") shouldBe 1
                }
            }

            test("監査の記録に失敗したら、注文も保存しない(同じトランザクション。記録のない業務の更新を残さない)") {
                val db = environment.newDatabase()
                db.superuser { c -> c.createStatement().use { it.execute("REVOKE INSERT ON audit.audit_log FROM order_service_app") } }
                testApplication {
                    orderService(db, idp)
                    val failed = place(idp.token())
                    failed.status.value shouldBeGreaterThanOrEqual 500
                    db.count("SELECT count(*) FROM orders") shouldBe 0
                    db.count("SELECT count(*) FROM audit.audit_log") shouldBe 0
                    // 5xx は保存しない(ADR-0022 §3)。同じキーで再試行できる
                    db.count("SELECT count(*) FROM idempotency_record") shouldBe 0
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
