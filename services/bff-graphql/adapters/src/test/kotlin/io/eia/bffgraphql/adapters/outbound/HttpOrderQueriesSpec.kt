package io.eia.bffgraphql.adapters.outbound

import io.eia.bffgraphql.application.model.OrderStatusView
import io.eia.bffgraphql.application.model.OrderView
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.sdk.http.HttpCallRejected
import io.eia.shared.sdk.http.HttpCallUnavailable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

private fun orderJson(
    id: String,
    status: String = "PLACED",
) = """
    {"id":"$id","customerId":"customer-1","status":"$status","orderedAt":"2026-11-03T00:00:00Z","extra":"読み飛ばす",
    "lines":[{"lineNumber":1,"productId":"product-1","sku":"SKU-1","quantity":2,
      "unitPrice":{"amount":"10.00","currency":"USD"},"lineAmount":{"amount":"20.00","currency":"USD"}}],
    "totalAmount":{"amount":"20.00","currency":"USD"},
    "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Tokyo","line1":"1-1"}}
    """.trimIndent()

/** order の API と Keycloak の代わり。注文の要求は [responses] の順に応える。 */
private class OrderFixture(
    vararg responses: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    private val queue = responses.toMutableList()
    val requests = mutableListOf<HttpRequestData>()
    var tokens = 0
    val client =
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/token")) {
                    tokens++
                    respond("""{"access_token":"t-$tokens","token_type":"Bearer","expires_in":300}""", HttpStatusCode.OK, JSON)
                } else {
                    requests += request
                    queue.removeFirst()(request)
                }
            },
        )
    val queries =
        HttpOrderQueries(
            client,
            URI("http://apisix:9080/"),
            ClientCredentialsTokenProvider(
                ClientCredentialsConfig(
                    URI("http://keycloak:8080/realms/eiaf/protocol/openid-connect/token"),
                    "eiaf-bff-graphql",
                    SecretName("BFF_CLIENT_SECRET"),
                ),
                client,
                EnvSecretProvider(mapOf("BFF_CLIENT_SECRET" to "secret")),
            ),
            Resilience(
                "order-api",
                ResilienceConfig(
                    attemptTimeout = 5.seconds,
                    retry = RetryPolicy(initialDelay = 1.milliseconds, maxAttempts = 2),
                    circuitBreaker = null,
                    retryBudget = null,
                ),
            ),
        )
}

class HttpOrderQueriesSpec :
    FunSpec({
        test("Gateway の公開パスに ids をまとめて送り、Bearer のトークンを付ける。契約の形を読み、知らない項目は読み飛ばす") {
            val fixture =
                OrderFixture({
                    respond("""{"orders":[${orderJson("a")},${orderJson("b")}],"notFound":["c"]}""", HttpStatusCode.OK, JSON)
                })

            val orders = fixture.queries.find(setOf("a", "b", "c")) as Result.Ok<List<OrderView>>

            val request = fixture.requests.single()
            request.url.encodedPath shouldBe "/sales/v1/orders"
            request.url.parameters.getAll("ids") shouldBe listOf("a", "b", "c")
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer t-1"
            orders.value.map { it.id } shouldBe listOf("a", "b")
            val line =
                orders.value
                    .first()
                    .lines
                    .single()
            line.sku shouldBe "SKU-1"
            line.quantity shouldBe 2L
            line.unitPrice.amount shouldBe "10.00"
            orders.value
                .first()
                .shippingAddress.region shouldBe null
        }

        test("一覧にない状態は UNKNOWN にする(Tolerant Reader)") {
            val fixture =
                OrderFixture({
                    respond("""{"orders":[${orderJson("a", status = "RETURNED")}],"notFound":[]}""", HttpStatusCode.OK, JSON)
                })

            (fixture.queries.find(setOf("a")) as Result.Ok<List<OrderView>>).value.single().status shouldBe OrderStatusView.UNKNOWN
        }

        test("401 ならトークンを捨てて取り直す") {
            val fixture =
                OrderFixture(
                    { respond("", HttpStatusCode.Unauthorized, JSON) },
                    { respond("""{"orders":[],"notFound":["a"]}""", HttpStatusCode.OK, JSON) },
                )

            fixture.queries.find(setOf("a")).shouldBeInstanceOf<Result.Ok<*>>()

            fixture.tokens shouldBe 2
            fixture.requests.last().headers[HttpHeaders.Authorization] shouldBe "Bearer t-2"
        }

        test("503 は再試行し、続けば Retryable のエラー") {
            val fixture =
                OrderFixture(
                    { respond("", HttpStatusCode.ServiceUnavailable, JSON) },
                    { respond("", HttpStatusCode.ServiceUnavailable, JSON) },
                )

            val result = fixture.queries.find(setOf("a"))

            fixture.requests.size shouldBe 2
            result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<HttpCallUnavailable>()
        }

        test("403(BFF の設定の誤り)は再試行せず NonRetryable") {
            val fixture = OrderFixture({ respond("", HttpStatusCode.Forbidden, JSON) })

            val result = fixture.queries.find(setOf("a"))

            fixture.requests.size shouldBe 1
            result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<HttpCallRejected>()
        }

        test("応答の形が契約と違えば NonRetryable(注文がないことにしない)") {
            val fixture = OrderFixture({ respond("""{"unexpected":true}""", HttpStatusCode.OK, JSON) })

            fixture.queries
                .find(setOf("a"))
                .shouldBeInstanceOf<Result.Err<DomainError>>()
                .error
                .shouldBeInstanceOf<UnexpectedError>()
        }
    })
