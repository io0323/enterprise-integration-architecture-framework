@file:Suppress("MagicNumber", "TooManyFunctions") // 状態コード・待ち時間・件数。クエリと障害の注入の部品

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.E2eEnvironment.GATEWAY
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.TEMPO
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.eia.tests.e2e.E2eEnvironment.token
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val STOREFRONT = "$GATEWAY/storefront/v1/graphql"
private val CALLER_SCOPES = setOf("bff-graphql-audience", "sales.order:read", "inventory.stock:read", "shipping.shipment:read")
private const val FULL_ORDER = "id status lines { sku stock { available } } shipment { status shipmentId }"

/** 空のメッセージの BatchGetStockRequest(gRPC の枠だけ。トークンを確かめる前に動くものの確認に使う)。 */
private val EMPTY_GRPC_FRAME = ByteArray(5)

private fun graphql(
    query: String,
    correlationId: String = "e2e-bff-${UUID.randomUUID()}",
): HttpResponse<String> =
    sendRespectingRateLimit(
        request(STOREFRONT, client = null)
            .header("Authorization", "Bearer ${token(Client.A, CALLER_SCOPES)}")
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", correlationId)
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("query", query) }.toString())),
    )

private fun HttpResponse<String>.bodyObject(): JsonObject = json(body()).jsonObject

private fun JsonObject.errorList(): List<JsonObject> = (this["errors"] as? JsonArray).orEmpty().map { it.jsonObject }

private fun JsonObject.extension(name: String): String =
    getValue("extensions")
        .jsonObject
        .getValue(name)
        .jsonPrimitive.content

private fun JsonObject.orderData(): JsonObject =
    getValue("data")
        .jsonObject
        .getValue("order")
        .jsonObject

/** 注文が出荷まで進むのを待つ(Saga。出荷の記録を BFF が読めるように)。 */
private fun shippedOrder(): String {
    val id = placeOrder()
    val shipped =
        eventually(Duration.ofMinutes(3), Duration.ofSeconds(2)) {
            val got = sendRespectingRateLimit(request("$ORDERS/$id").GET())
            json(got.body())
                .jsonObject["status"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it == "SHIPPED" }
        }
    withClue("注文 $id が SHIPPED にならない") { shipped.shouldNotBeNull() }
    return id
}

private val infra: Path = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local")

/** 障害の注入(下流のコンテナの停止・起動)。在庫と出荷は saga の profile のサービス。 */
private fun compose(vararg args: String) {
    val command =
        listOf("docker", "compose", "-f", infra.resolve("docker-compose.yml").toString()) +
            listOf("--env-file", infra.resolve("images.env").toString(), "--env-file", infra.resolve(".env").toString()) +
            listOf("--profile", "core", "--profile", "saga") + args
    val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    check(process.waitFor(3, TimeUnit.MINUTES) && process.exitValue() == 0) { "docker compose ${args.toList()} が失敗しました" }
}

/** 下流のコンテナを止めている間に [block] を実行する。失敗しても必ず起動し直す(後のシナリオを止めない)。 */
private fun whileStopped(
    service: String,
    block: () -> Unit,
) {
    compose("stop", service)
    try {
        block()
    } finally {
        compose("up", "-d", "--no-deps", "--no-build", "--wait", service)
    }
}

/** `eia.graphql.partial_responses` の [service] の合計(まだなければ null)。 */
private fun partialResponses(service: String): Double? =
    json(get("$PROMETHEUS/api/v1/query?query=${encode("""sum(eia_graphql_partial_responses_total{service="$service"})""")}"))
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("result")
        .jsonArray
        .firstOrNull()
        ?.jsonObject
        ?.getValue("value")
        ?.jsonArray
        ?.get(1)
        ?.jsonPrimitive
        ?.content
        ?.toDouble()

/** [correlationId] の apisix の span を持つトレースの ID。 */
private fun gatewayTraces(correlationId: String): List<String> {
    val query = """{ resource.service.name = "apisix" && span.x-correlation-id = "$correlationId" }"""
    return json(get("$TEMPO/api/search?limit=10&q=${encode(query)}"))
        .jsonObject["traces"]
        ?.jsonArray
        .orEmpty()
        .map {
            it.jsonObject
                .getValue("traceID")
                .jsonPrimitive.content
        }
}

/** 開発用の証明書で [port] の gRPC に、トークンなしの空の要求を送る(HTTP/2)。 */
private fun grpcWithoutToken(
    port: Int,
    path: String,
    clientCert: String?,
): HttpResponse<Void> {
    val client =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .sslContext(sslContext(clientCert))
            .build()
    val call =
        HttpRequest
            .newBuilder(URI.create("https://localhost:$port$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/grpc")
            .POST(HttpRequest.BodyPublishers.ofByteArray(EMPTY_GRPC_FRAME))
            .build()
    return client.send(call, HttpResponse.BodyHandlers.discarding())
}

/**
 * ROADMAP P13 の DoD: 契約から生成したスタブで通信する。mTLS なしの gRPC 接続を拒否する。GraphQL の過大なクエリを拒否する。
 * ADR-0043 の部分応答(§6)と、Gateway → BFF → 3 つのサービスの全体を、公開されたエンドポイントとコンテナの停止だけで確かめる。
 * 基盤は `make up PROFILE="order saga bff"`。
 */
class BffE2E :
    FunSpec({
        // 注文を読めることを、順番に依存せずに使う(Saga を待つので 1 回だけ作る)
        val orderId by lazy { shippedOrder() }

        test("Gateway → BFF → 注文(REST)+ 在庫と出荷(契約から生成したスタブの gRPC)を 1 回で返す(DoD)") {
            // 起動の直後は、下流の最初の呼び出しがタイムアウトして部分応答になることがある(ADR-0043 §6)。解消するまで待つ
            val body =
                withClue("注文 $orderId の集約がエラーなしで返らない") {
                    eventually {
                        graphql("""{ order(id: "$orderId") { $FULL_ORDER } }""")
                            .takeIf { it.statusCode() == 200 }
                            ?.bodyObject()
                            ?.takeIf { it.errorList().isEmpty() }
                    }.shouldNotBeNull()
                }
            val order = body.orderData()
            order.getValue("id").jsonPrimitive.content shouldBe orderId
            order.getValue("status").jsonPrimitive.content shouldBe "SHIPPED"
            // 在庫と出荷は実際のサービスの値(シードの在庫と、Saga が作った出荷)
            val line =
                order
                    .getValue("lines")
                    .jsonArray
                    .single()
                    .jsonObject
            line.getValue("sku").jsonPrimitive.content shouldBe "SKU-1"
            (
                line
                    .getValue("stock")
                    .jsonObject
                    .getValue("available")
                    .jsonPrimitive.long > 0
            ) shouldBe true
            val shipment = order.getValue("shipment").jsonObject
            shipment.getValue("status").jsonPrimitive.content shouldBe "SHIPPED"
            (shipment.getValue("shipmentId") is JsonNull) shouldBe false
        }

        context("mTLS なしの gRPC 接続を拒否する(DoD。ADR-0042 §5)") {
            val services =
                mapOf(
                    19445 to "/io.eia.inventory.v1.StockQueryService/BatchGetStock",
                    19446 to "/io.eia.shipping.v1.ShipmentQueryService/BatchGetShipments",
                )
            services.forEach { (port, path) ->
                test("$path: クライアント証明書がなければ接続できない") {
                    shouldThrow<IOException> { grpcWithoutToken(port, path, clientCert = null) }
                }

                test("$path: 許可された証明書(SAN bff-graphql)なら接続でき、トークンがないので UNAUTHENTICATED(拒否の理由が mTLS であることの対照)") {
                    grpcWithoutToken(port, path, clientCert = "bff-graphql")
                        .headers()
                        .firstValue("grpc-status")
                        .orElse(null) shouldBe "16"
                }
            }
        }

        context("過大なクエリを拒否する(DoD。ADR-0043 §4)") {
            val twenty = List(20) { "\"order-$it\"" }.joinToString(",", "[", "]")
            val heavy = "orders { shipment { status } lines { stock { available } } }"
            mapOf(
                "ids が 21 件" to ("maxItems" to """{ orders(ids: ${List(21) { "\"order-$it\"" }}) { notFound } }"""),
                "別名で並べた ID の合計が 21 件" to ("maxItems" to """{ a: orders(ids: $twenty) { notFound } b: order(id: "other") { id } }"""),
                "別名が 11 個" to ("maxAliases" to "{ ${(1..11).joinToString(" ") { """a$it: order(id: "x") { id }""" }} }"),
                "複雑度が 200 を超える" to ("maxComplexity" to """{ a: orders(ids: $twenty) { $heavy } b: orders(ids: $twenty) { $heavy } }"""),
            ).forEach { (label, expected) ->
                val (limit, query) = expected
                test("$label は 400 QUERY_LIMIT_EXCEEDED($limit)") {
                    val response = graphql(query)
                    response.statusCode() shouldBe 400
                    val error =
                        response
                            .bodyObject()
                            .errorList()
                            .single()
                    error.extension("code") shouldBe "QUERY_LIMIT_EXCEEDED"
                    error.extension("limit") shouldBe limit
                }
            }

            test("イントロスペクションは無効") {
                val response = graphql("{ __schema { types { name } } }")
                response.statusCode() shouldBe 400
                response.bodyObject()["data"] shouldBe null
            }
        }

        context("一部のサービスが止まっていても、読める項目は返す(部分応答。ADR-0043 §6)") {
            test("出荷を止めると、出荷だけ null + UPSTREAM_UNAVAILABLE。注文と在庫は返す。HTTP は 200。止まったことをメトリクスで数える。戻すと全部返す") {
                val before = partialResponses("shipping") ?: 0.0
                whileStopped("shipping-service") {
                    val response = graphql("""{ order(id: "$orderId") { $FULL_ORDER } }""")

                    response.statusCode() shouldBe 200
                    val body = response.bodyObject()
                    val order = body.orderData()
                    order.getValue("id").jsonPrimitive.content shouldBe orderId
                    (order.getValue("shipment") is JsonNull) shouldBe true
                    (
                        order
                            .getValue("lines")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("stock") is JsonNull
                    ) shouldBe false
                    val error = body.errorList().single()
                    error.extension("code") shouldBe "UPSTREAM_UNAVAILABLE"
                    error.extension("service") shouldBe "shipping"
                    error
                        .getValue("extensions")
                        .jsonObject
                        .getValue("retryable")
                        .jsonPrimitive.boolean shouldBe true

                    withClue("eia.graphql.partial_responses{service=shipping} が増える") {
                        eventually { partialResponses("shipping")?.takeIf { it > before } }.shouldNotBeNull()
                    }
                }
                withClue("起動し直すと出荷も読める(Circuit Breaker の Half-Open を待つ)") {
                    eventually {
                        graphql("""{ order(id: "$orderId") { shipment { status } } }""")
                            .bodyObject()
                            .takeIf { it.errorList().isEmpty() }
                    }.shouldNotBeNull()
                }
            }

            test("在庫を止めると、在庫だけ null + UPSTREAM_UNAVAILABLE。注文と出荷は返す") {
                whileStopped("inventory-service") {
                    val response = graphql("""{ order(id: "$orderId") { $FULL_ORDER } }""")

                    response.statusCode() shouldBe 200
                    val body = response.bodyObject()
                    val order = body.orderData()
                    (order.getValue("shipment") is JsonNull) shouldBe false
                    (
                        order
                            .getValue("lines")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("stock") is JsonNull
                    ) shouldBe true
                    body.errorList().single().extension("service") shouldBe "inventory"
                }
            }
        }

        test("トレース: Gateway → BFF → 在庫と出荷は 1 つのトレース。BFF → Gateway → order は別のトレースで、X-Correlation-Id でつながる(ADR-0043 §9)") {
            val correlationId = "e2e-bff-trace-${UUID.randomUUID()}"
            eventually {
                graphql("""{ order(id: "$orderId") { $FULL_ORDER } }""", correlationId)
                    .bodyObject()
                    .takeIf { it.errorList().isEmpty() }
            }.shouldNotBeNull()

            // Gateway は受信した traceparent を捨てる(ADR-0023 §4)ので、BFF が Gateway を呼ぶと新しいトレースになる
            val all =
                eventually(Duration.ofMinutes(2), Duration.ofSeconds(3)) {
                    val services = gatewayTraces(correlationId).flatMap { id -> spans(id).map { id to it.service } }
                    services.takeIf { found ->
                        found.map { it.second }.containsAll(listOf("bff-graphql", "inventory-service", "shipping-service", "order-service"))
                    }
                }.shouldNotBeNull()
            // 部分応答が解消するまで問い合わせ直すと、同じ X-Correlation-Id のトレースが複数できる。回数によらず、分かれ方を確かめる
            val servicesOf = all.groupBy({ it.first }, { it.second }).values.map { it.toSet() }
            val front = setOf("apisix", "bff-graphql", "inventory-service", "shipping-service")
            withClue("Gateway → BFF → 在庫と出荷が 1 つのトレース: $servicesOf") { servicesOf.any { it.containsAll(front) } shouldBe true }
            withClue("order は BFF を含まない別のトレース: $servicesOf") {
                val orderTraces = servicesOf.filter { "order-service" in it }
                orderTraces.any { it.contains("apisix") } shouldBe true
                orderTraces.none { "bff-graphql" in it } shouldBe true
            }
        }
    })
