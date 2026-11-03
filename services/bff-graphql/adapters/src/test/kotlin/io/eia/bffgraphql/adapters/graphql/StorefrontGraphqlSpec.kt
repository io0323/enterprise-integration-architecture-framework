@file:Suppress("MagicNumber") // テストの件数・時間

package io.eia.bffgraphql.adapters.graphql

import io.eia.bffgraphql.application.model.AddressView
import io.eia.bffgraphql.application.model.MoneyView
import io.eia.bffgraphql.application.model.OrderLineView
import io.eia.bffgraphql.application.model.OrderStatusView
import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentStatusView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.bffgraphql.application.port.outbound.OrderQueries
import io.eia.bffgraphql.application.port.outbound.ShipmentQueries
import io.eia.bffgraphql.application.port.outbound.StockQueries
import io.eia.bffgraphql.application.usecase.LoadOrdersService
import io.eia.bffgraphql.application.usecase.LoadShipmentsService
import io.eia.bffgraphql.application.usecase.LoadStockLevelsService
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val ALL_SCOPES =
    "sales.order:read sales.order:read-address inventory.stock:read shipping.shipment:read"
private const val SCOPES_HEADER = "X-Test-Scopes"

private fun money() = MoneyView("10.00", "USD")

private fun order(
    id: String,
    vararg skus: String,
) = OrderView(
    id = id,
    customerId = "customer-1",
    status = OrderStatusView.PLACED,
    orderedAt = "2026-11-03T00:00:00Z",
    lines = skus.mapIndexed { index, sku -> OrderLineView(index + 1, "product-$sku", sku, 2, money(), money()) },
    totalAmount = money(),
    shippingAddress = AddressView("JP", "100-0001", null, "Tokyo", "1-1", null),
)

/** 呼ばれた引数を記録する下流。[result] で結果を差し替える。 */
private class FakeUpstream<V>(
    private val find: (Set<String>) -> List<V>,
) {
    val calls = mutableListOf<Set<String>>()
    var failure: DomainError? = null
    var delayBy: Duration = Duration.ZERO

    suspend fun read(keys: Set<String>): Result<List<V>, DomainError> {
        calls += keys
        if (delayBy.isPositive()) delay(delayBy)
        return failure?.let { err(it) } ?: ok(find(keys))
    }
}

private class Fixture(
    budget: UpstreamBudget = UpstreamBudget(),
    introspection: Boolean = false,
) {
    val orders =
        FakeUpstream { ids ->
            ids.filter { it != "order-none" }.map { order(it, "SKU-1", "SKU-2") }
        }
    val stock =
        FakeUpstream { skus ->
            skus.filter { it != "SKU-2" }.map { StockView(it, 7, "2026-11-03T00:00:00Z") }
        }
    val shipments =
        FakeUpstream { ids ->
            ids.filter { it == "order-shipped" }.map {
                ShipmentView(it, ShipmentStatusView.SHIPPED, "ship-1", "2026-11-03T00:00:00Z", "JP")
            }
        }
    val metrics: InMemoryMetricReader = InMemoryMetricReader.create()
    private val meter =
        SdkMeterProvider
            .builder()
            .registerMetricReader(metrics)
            .build()
            .get("test")

    val engine =
        StorefrontGraphql(
            StorefrontGraphql.contractSdl(),
            LoadOrdersService(
                object : OrderQueries {
                    override suspend fun find(ids: Set<String>) = orders.read(ids)
                },
            ),
            LoadStockLevelsService(
                object : StockQueries {
                    override suspend fun find(skus: Set<String>) = stock.read(skus)
                },
            ),
            LoadShipmentsService(
                object : ShipmentQueries {
                    override suspend fun find(orderIds: Set<String>) = shipments.read(orderIds)
                },
            ),
            GraphqlMetrics(meter),
            budget,
            introspection,
        )

    /** どの下流も呼ばれていない。 */
    fun assertNoUpstreamCalls() {
        orders.calls.shouldBeEmpty()
        stock.calls.shouldBeEmpty()
        shipments.calls.shouldBeEmpty()
    }

    /** [name] のカウンタの、属性 [key] の値ごとの合計。 */
    fun counter(
        name: String,
        key: String,
    ): Map<String, Long> =
        metrics
            .collectAllMetrics()
            .filter { it.name == name }
            .flatMap { it.longSumData.points }
            .associate { point ->
                point.attributes
                    .asMap()
                    .entries
                    .first { it.key.key == key }
                    .value
                    .toString() to point.value
            }
}

private fun ApplicationTestBuilder.storefront(fixture: Fixture) {
    application {
        install(Authentication) {
            provider {
                authenticate { context ->
                    val scopes = context.call.request.headers[SCOPES_HEADER]
                    if (scopes != null) {
                        context.principal(
                            VerifiedToken(
                                "iss",
                                "sub",
                                "client-a",
                                listOf("bff-graphql"),
                                scopes.split(' ').filter { it.isNotEmpty() }.toSet(),
                                Clock.System.now(),
                                Clock.System.now() + 1.hours,
                                null,
                            ),
                        )
                    }
                }
            }
        }
        routing { authenticate { storefrontGraphql(fixture.engine) } }
    }
}

private suspend fun ApplicationTestBuilder.query(
    query: String,
    variables: JsonObject? = null,
    scopes: String = ALL_SCOPES,
): HttpResponse =
    client.post("/v1/graphql") {
        header(SCOPES_HEADER, scopes)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("query", query)
                variables?.let { put("variables", it) }
            }.toString(),
        )
    }

private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

private fun JsonObject.errors(): List<JsonObject> = (this["errors"] as? JsonArray).orEmpty().map { it.jsonObject }

private fun JsonObject.code(): String =
    getValue("extensions")
        .jsonObject
        .getValue("code")
        .jsonPrimitive.content

private fun JsonObject.limit(): String =
    getValue("extensions")
        .jsonObject
        .getValue("limit")
        .jsonPrimitive.content

private fun JsonObject.pathOf(): String = getValue("path").jsonArray.joinToString(".") { it.jsonPrimitive.content }

private const val FULL_ORDER = """
    id status totalAmount { amount currency }
    shippingAddress { city }
    shipment { status shipmentId }
    lines { sku quantity stock { available updatedAt } }
"""

/** 下流を呼ばずに、上限の超過として 400 で拒否されること。 */
private suspend fun ApplicationTestBuilder.shouldBeRejected(
    fixture: Fixture,
    limit: String,
    query: String,
    variables: JsonObject? = null,
) {
    val response = query(query, variables)
    response.status shouldBe HttpStatusCode.BadRequest
    val body = response.json()
    body["data"] shouldBe null
    body.errors().single().code() shouldBe ErrorCodes.QUERY_LIMIT_EXCEEDED
    body.errors().single().limit() shouldBe limit
    fixture.assertNoUpstreamCalls()
}

/** ids を [count] 件並べた引数の文字列。 */
private fun ids(count: Int) = List(count) { "\"order-$it\"" }.joinToString(",", "[", "]")

class StorefrontGraphqlSpec :
    FunSpec({
        test("契約の SDL をリソースに含めていて、契約のファイルと同じ") {
            val contract =
                java.io
                    .File(checkNotNull(System.getProperty("eia.rootDir")), "contracts/graphql/storefront.v1.graphqls")
                    .readText()
            StorefrontGraphql.contractSdl() shouldBe contract
        }

        context("集約") {
            test("注文 + 在庫 + 出荷を 1 回で返す。「ない」はエラーなしの null") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body = query("""{ order(id: "order-shipped") { $FULL_ORDER } }""").json()

                    body.errors().shouldBeEmpty()
                    val order =
                        body
                            .getValue("data")
                            .jsonObject
                            .getValue("order")
                            .jsonObject
                    order.getValue("status").jsonPrimitive.content shouldBe "PLACED"
                    order
                        .getValue("shippingAddress")
                        .jsonObject
                        .getValue("city")
                        .jsonPrimitive.content shouldBe "Tokyo"
                    order
                        .getValue("shipment")
                        .jsonObject
                        .getValue("shipmentId")
                        .jsonPrimitive.content shouldBe "ship-1"
                    val lines = order.getValue("lines").jsonArray.map { it.jsonObject }
                    lines[0].getValue("quantity").jsonPrimitive.int shouldBe 2
                    lines[0]
                        .getValue("stock")
                        .jsonObject
                        .getValue("available")
                        .jsonPrimitive.int shouldBe 7
                    // 台帳にない SKU
                    lines[1].getValue("stock") shouldBe JsonNull
                }
            }

            test("ない注文は order が null で、エラーなし。下流の在庫と出荷は呼ばない") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body = query("""{ order(id: "order-none") { $FULL_ORDER } }""").json()

                    body.errors().shouldBeEmpty()
                    body.getValue("data").jsonObject.getValue("order") shouldBe JsonNull
                    fixture.stock.calls.shouldBeEmpty()
                    fixture.shipments.calls.shouldBeEmpty()
                }
            }

            test("orders は指定した順に返し、ない ID は notFound に返す。重複は 1 件") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body = query("""{ orders(ids: ["b", "order-none", "a", "b"]) { orders { id } notFound } }""").json()

                    val batch =
                        body
                            .getValue("data")
                            .jsonObject
                            .getValue("orders")
                            .jsonObject
                    batch.getValue("orders").jsonArray.map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    } shouldBe listOf("b", "a")
                    batch.getValue("notFound").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("order-none")
                }
            }
        }

        context("N+1 にしない(ADR-0043 §5)") {
            test("複数の注文でも、注文・在庫・出荷をそれぞれ 1 回でまとめて読み、SKU の重複を除く") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    query("""{ orders(ids: ["a", "b", "c"]) { orders { $FULL_ORDER } } }""").status shouldBe HttpStatusCode.OK
                }

                fixture.orders.calls shouldBe listOf(setOf("a", "b", "c"))
                fixture.stock.calls shouldBe listOf(setOf("SKU-1", "SKU-2"))
                fixture.shipments.calls shouldBe listOf(setOf("a", "b", "c"))
            }

            test("別名で並べた order も 1 回にまとめる") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    query("""{ x: order(id: "a") { id } y: order(id: "b") { id } z: order(id: "a") { id } }""").status shouldBe
                        HttpStatusCode.OK
                }

                fixture.orders.calls shouldBe listOf(setOf("a", "b"))
            }
        }

        context("過大なクエリの拒否(ROADMAP P13 の DoD。ADR-0043 §4)") {
            test("上限は契約の SDL から読む") {
                Fixture().engine.limits shouldBe
                    QueryLimits(maxDepth = 8, maxAliases = 10, maxComplexity = 200, maxTokens = 2000, maxBodyBytes = 16384)
            }

            test("ids が 20 件を超えるクエリは、下流を呼ばずに拒否する(承認時の条件 3)。ちょうど 20 件は通す") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    shouldBeRejected(fixture, "maxItems", """{ orders(ids: ${ids(21)}) { orders { id } } }""")
                    query("""{ orders(ids: ${ids(20)}) { orders { id } } }""").status shouldBe HttpStatusCode.OK
                }
            }

            test("変数で渡した ids も同じ(実行する前に確かめる)") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val variables = Json.parseToJsonElement("""{"ids": ${ids(21)}}""").jsonObject
                    shouldBeRejected(
                        fixture,
                        "maxItems",
                        """query(${'$'}ids: [ID!]!) { orders(ids: ${'$'}ids) { orders { id } } }""",
                        variables,
                    )
                }
            }

            test("別名の数の上限") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val aliases = (1..11).joinToString(" ") { """a$it: order(id: "x") { id }""" }
                    shouldBeRejected(fixture, "maxAliases", "{ $aliases }")
                }
            }

            test("フラグメントで隠した別名も、展開して数える") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val aliases = (1..4).joinToString(" ") { "f$it: id" }
                    val spreads = (1..3).joinToString(" ") { """o$it: order(id: "x") { ...Many }""" }
                    shouldBeRejected(fixture, "maxAliases", "{ $spreads } fragment Many on Order { $aliases }")
                }
            }

            test("同じフラグメントを何度使っても、使った回数だけ複雑度を数える") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val fragment = "fragment Heavy on OrderBatch { orders { shipment { status } lines { stock { available } } } }"
                    shouldBeRejected(
                        fixture,
                        "maxComplexity",
                        """{ x: orders(ids: ${ids(20)}) { ...Heavy } y: orders(ids: ${ids(20)}) { ...Heavy } } $fragment""",
                    )
                }
            }

            test("入れ子のフラグメントで項目を指数的に増やすクエリは、展開を途中で止めて拒否する") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)

                    fun tenAliases(field: (Int) -> String) = (0..9).joinToString(" ") { "a$it: ${field(it)}" }
                    val query =
                        "{ ${tenAliases { """order(id: "x") { ...Lines }""" }} } " +
                            "fragment Lines on Order { ${tenAliases { "lines { ...Prices }" }} } " +
                            "fragment Prices on OrderLine { ${tenAliases { "unitPrice { ...Fields }" }} } " +
                            "fragment Fields on Money { ${tenAliases { "amount" }} }"

                    shouldBeRejected(fixture, "maxComplexity", query)
                }
            }

            test("深さの上限(イントロスペクションの型の入れ子で確かめる。SDL の普通のクエリは上限より浅い)") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val nested = "ofType { ".repeat(8) + "name" + " }".repeat(8)
                    shouldBeRejected(fixture, "maxDepth", """{ __type(name: "Order") { fields { type { $nested } } } }""")
                }
            }

            test("複雑度の上限: 正当な最大のクエリは通り、別名で 2 回並べると拒否する") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val full = """orders(ids: ${ids(20)}) { orders { shipment { status } lines { stock { available } } } }"""
                    query("{ $full }").status shouldBe HttpStatusCode.OK
                    fixture.orders.calls.clear()
                    fixture.stock.calls.clear()
                    fixture.shipments.calls.clear()
                    shouldBeRejected(fixture, "maxComplexity", "{ x: $full y: $full }")
                }
            }

            test("トークンの数の上限(パーサが止める)") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val fields = "id ".repeat(2001)
                    val response = query("""{ order(id: "x") { $fields } }""")

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldContain "errors"
                    fixture.assertNoUpstreamCalls()
                }
            }

            test("本文の大きさの上限") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val response = query("""{ order(id: "x") { id } } # ${"x".repeat(16384)}""")

                    response.status shouldBe HttpStatusCode.BadRequest
                    response
                        .json()
                        .errors()
                        .single()
                        .code() shouldBe ErrorCodes.BAD_REQUEST
                    fixture.assertNoUpstreamCalls()
                }
            }

            test("拒否した上限を数える") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    shouldBeRejected(fixture, "maxItems", """{ orders(ids: ${ids(21)}) { orders { id } } }""")
                }
                fixture.counter("eia.graphql.rejected_queries", "limit") shouldBe mapOf("maxItems" to 1L)
            }
        }

        context("受け付ける形") {
            test("複数の操作をまとめた配列と、JSON でない本文は 400") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    listOf("""[{"query":"{ __typename }"}]""", "not json").forEach { body ->
                        val response =
                            client.post("/v1/graphql") {
                                header(SCOPES_HEADER, ALL_SCOPES)
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        response.status shouldBe HttpStatusCode.BadRequest
                    }
                }
            }

            test("GET は受け付けない") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val response =
                        client.get("/v1/graphql?query=%7B__typename%7D") { header(SCOPES_HEADER, ALL_SCOPES) }
                    (response.status == HttpStatusCode.OK) shouldBe false
                }
            }

            test("トークンがなければ 401") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    client
                        .post("/v1/graphql") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"query":"{ __typename }"}""")
                        }.status shouldBe HttpStatusCode.Unauthorized
                }
            }

            test("構文の誤りと、ない項目は 400 で、項目の候補を出さない") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    query("{ order(id: ").status shouldBe HttpStatusCode.BadRequest
                    val unknown = query("""{ order(id: "x") { idd } }""")
                    unknown.status shouldBe HttpStatusCode.BadRequest
                    unknown.bodyAsText() shouldNotContain "Did you mean"
                    unknown.bodyAsText() shouldNotContain "customerId"
                    fixture.assertNoUpstreamCalls()
                }
            }
        }

        context("イントロスペクション") {
            test("既定で無効(本番)") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body = query("{ __schema { queryType { name } } }").bodyAsText()
                    body shouldNotContain "\"Query\""
                    body shouldContain "errors"
                }
            }

            test("設定で有効にできる(ローカル)") {
                val fixture = Fixture(introspection = true)
                testApplication {
                    storefront(fixture)
                    query("{ __schema { queryType { name } } }").bodyAsText() shouldContain "\"Query\""
                }
            }
        }

        context("一部のサービスが止まっているとき(ADR-0043 §6)") {
            test("在庫が読めなければ、在庫だけ null + errors で、ほかは返す") {
                val fixture = Fixture()
                fixture.stock.failure = UnavailableError("secret detail of inventory")
                testApplication {
                    storefront(fixture)
                    val response = query("""{ order(id: "order-shipped") { id shipment { status } lines { sku stock { available } } } }""")

                    response.status shouldBe HttpStatusCode.OK
                    val text = response.bodyAsText()
                    text shouldNotContain "secret detail"
                    val body = response.json()
                    val order =
                        body
                            .getValue("data")
                            .jsonObject
                            .getValue("order")
                            .jsonObject
                    order
                        .getValue("shipment")
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive.content shouldBe "SHIPPED"
                    order.getValue("lines").jsonArray.forEach { it.jsonObject.getValue("stock") shouldBe JsonNull }
                    val errors = body.errors()
                    errors.map { it.pathOf() }.toSet() shouldBe setOf("order.lines.0.stock", "order.lines.1.stock")
                    errors.forEach {
                        it.code() shouldBe ErrorCodes.UPSTREAM_UNAVAILABLE
                        val extensions = it.getValue("extensions").jsonObject
                        extensions.getValue("service").jsonPrimitive.content shouldBe "inventory"
                        extensions.getValue("retryable").jsonPrimitive.content shouldBe "true"
                    }
                }
                // 部分応答は HTTP 200 なので RED の Errors に出ない。別に数える(1 つの応答で 1 回)
                fixture.counter("eia.graphql.partial_responses", "service") shouldBe mapOf("inventory" to 1L)
            }

            test("出荷が読めなければ、「まだ出荷していない」と区別できる(エラーつきの null)") {
                val fixture = Fixture()
                fixture.shipments.failure = UnavailableError("shipping")
                testApplication {
                    storefront(fixture)
                    val body = query("""{ order(id: "x") { id shipment { status } } }""").json()

                    val order =
                        body
                            .getValue("data")
                            .jsonObject
                            .getValue("order")
                            .jsonObject
                    order.getValue("shipment") shouldBe JsonNull
                    body.errors().single().pathOf() shouldBe "order.shipment"
                }
            }

            test("注文が読めなければ、order が null + errors で、在庫と出荷は呼ばない。ほかの項目のエラーにしない") {
                val fixture = Fixture()
                fixture.orders.failure = UnavailableError("orders")
                testApplication {
                    storefront(fixture)
                    val response = query("""{ order(id: "x") { $FULL_ORDER } }""")

                    response.status shouldBe HttpStatusCode.OK
                    val body = response.json()
                    body.getValue("data").jsonObject.getValue("order") shouldBe JsonNull
                    body.errors().single().pathOf() shouldBe "order"
                    fixture.stock.calls.shouldBeEmpty()
                    fixture.shipments.calls.shouldBeEmpty()
                }
            }

            test("再試行しても直らない失敗は retryable: false") {
                val fixture = Fixture()
                fixture.shipments.failure = UnexpectedError("設定の誤り")
                testApplication {
                    storefront(fixture)
                    val error = query("""{ order(id: "x") { shipment { status } } }""").json().errors().single()

                    error.code() shouldBe ErrorCodes.UPSTREAM_ERROR
                    error
                        .getValue("extensions")
                        .jsonObject
                        .getValue("retryable")
                        .jsonPrimitive.content shouldBe "false"
                }
            }

            test("遅いサービスは全体の予算切れより先に打ち切り、取れた部分を返す") {
                val budget = UpstreamBudget(total = 1.seconds, orderSlice = 300.milliseconds, reserve = 200.milliseconds)
                val fixture = Fixture(budget)
                fixture.stock.delayBy = 1.hours
                testApplication {
                    storefront(fixture)
                    val started = TimeSource.Monotonic.markNow()
                    val response = query("""{ order(id: "order-shipped") { id shipment { status } lines { stock { available } } } }""")
                    val elapsed = started.elapsedNow()

                    response.status shouldBe HttpStatusCode.OK
                    elapsed shouldBeLessThan 5.seconds
                    val body = response.json()
                    body
                        .getValue("data")
                        .jsonObject
                        .getValue("order")
                        .jsonObject
                        .getValue("shipment") shouldBe
                        Json.parseToJsonElement("""{"status":"SHIPPED"}""")
                    body.errors().map { it.pathOf() }.toSet() shouldBe setOf("order.lines.0.stock", "order.lines.1.stock")
                }
            }

            test("注文に使える時間は orderSlice まで") {
                val budget = UpstreamBudget(total = 30.seconds, orderSlice = 200.milliseconds, reserve = 100.milliseconds)
                val fixture = Fixture(budget)
                fixture.orders.delayBy = 1.hours
                testApplication {
                    storefront(fixture)
                    val started = TimeSource.Monotonic.markNow()
                    val body = query("""{ order(id: "x") { id } }""").json()

                    started.elapsedNow() shouldBeLessThan 10.seconds
                    body.errors().single().pathOf() shouldBe "order"
                }
            }
        }

        context("項目の認可(ADR-0043 §7)") {
            test("住所のスコープがなければ、住所だけ null + FORBIDDEN で、ほかは返す") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body =
                        query(
                            """{ order(id: "x") { id shippingAddress { city } } }""",
                            scopes = "sales.order:read",
                        ).json()

                    val order =
                        body
                            .getValue("data")
                            .jsonObject
                            .getValue("order")
                            .jsonObject
                    order.getValue("id").jsonPrimitive.content shouldBe "x"
                    order.getValue("shippingAddress") shouldBe JsonNull
                    val error = body.errors().single()
                    error.pathOf() shouldBe "order.shippingAddress"
                    error.code() shouldBe ErrorCodes.FORBIDDEN
                    error
                        .getValue("extensions")
                        .jsonObject
                        .getValue("scope")
                        .jsonPrimitive.content shouldBe "sales.order:read-address"
                }
            }

            test("在庫と出荷のスコープがなければ、その下流を呼ばない") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body =
                        query(
                            """{ order(id: "x") { shipment { status } lines { stock { available } } } }""",
                            scopes = "sales.order:read",
                        ).json()

                    body.errors().map { it.code() }.toSet() shouldBe setOf(ErrorCodes.FORBIDDEN)
                    fixture.stock.calls.shouldBeEmpty()
                    fixture.shipments.calls.shouldBeEmpty()
                }
            }

            test("注文のスコープがなければ、下流を呼ばない") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    val body =
                        query(
                            """{ order(id: "x") { id } orders(ids: ["y"]) { notFound } }""",
                            scopes = "inventory.stock:read",
                        ).json()

                    body.errors().map { it.code() } shouldBe listOf(ErrorCodes.FORBIDDEN, ErrorCodes.FORBIDDEN)
                    fixture.assertNoUpstreamCalls()
                }
            }

            test("住所を読んだことを、値を残さずに数える") {
                val fixture = Fixture()
                testApplication {
                    storefront(fixture)
                    query("""{ order(id: "x") { shippingAddress { city } } }""")
                }
                fixture.counter("eia.graphql.sensitive_field_reads", "field") shouldBe mapOf("Order.shippingAddress" to 1L)
            }

            test("SDL の宣言が足りなければ起動しない(Query の項目のスコープ・@queryLimits・スコープのある項目の非 null)") {
                val sdl = StorefrontGraphql.contractSdl()
                listOf(
                    sdl.replace("order(id: ID!): Order @requiresScope(scope: \"sales.order:read\")", "order(id: ID!): Order"),
                    sdl.replace(
                        "schema @queryLimits(maxDepth: 8, maxAliases: 10, maxComplexity: 200, maxTokens: 2000, maxBodyBytes: 16384) {",
                        "schema {",
                    ),
                    sdl.replace(
                        "shipment: Shipment @requiresScope(scope: \"shipping.shipment:read\")",
                        "shipment: Shipment! @requiresScope(scope: \"shipping.shipment:read\")",
                    ),
                ).forEach { broken ->
                    (broken == sdl) shouldBe false
                    io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                        StorefrontSchema.of(
                            broken,
                            GraphqlMetrics(
                                io.opentelemetry.api.OpenTelemetry
                                    .noop()
                                    .getMeter("test"),
                            ),
                        )
                    }
                }
            }
        }
    })
