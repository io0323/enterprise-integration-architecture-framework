package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.order.HttpOrderApi
import io.eia.b2bgateway.application.port.outbound.Placement
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.sdk.http.HttpCallRejected
import io.eia.shared.sdk.http.HttpCallUnavailable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
private val PROBLEM = headersOf(HttpHeaders.ContentType, "application/problem+json")
private const val KEY = "b2b:partner01:ICR1:1"
private val BODY = """{"customerId":"cust-1"}""".encodeToByteArray()

/** order の API と Keycloak の代わり(MockEngine)。注文の要求は [responses] の順に応える。 */
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
                    respond(
                        """{"access_token":"t-$tokens","token_type":"Bearer","expires_in":300,"scope":"sales.order:write"}""",
                        HttpStatusCode.OK,
                        JSON,
                    )
                } else {
                    requests += request
                    queue.removeFirst()(request)
                }
            },
        )
    val api =
        HttpOrderApi(
            client,
            URI("http://apisix:9080/"),
            ClientCredentialsTokenProvider(
                ClientCredentialsConfig(
                    URI("http://keycloak:8080/realms/eiaf/protocol/openid-connect/token"),
                    "eiaf-b2b-gateway",
                    SecretName("B2B_GATEWAY_CLIENT_SECRET"),
                ),
                client,
                EnvSecretProvider(mapOf("B2B_GATEWAY_CLIENT_SECRET" to "secret")),
            ),
            Resilience(
                "order-api",
                ResilienceConfig(
                    attemptTimeout = 5.seconds,
                    retry = RetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds, maxDelay = 1.milliseconds),
                ),
            ),
        )

    suspend fun place() = api.place(KEY, BODY)
}

private fun problem(type: String) = """{"type":"https://eiaf.example/problems/$type","title":"x","status":422}"""

class HttpOrderApiSpec :
    FunSpec({
        test("Gateway の公開のパスに、トークン・Idempotency-Key・固定した本文をそのまま送り、201 の注文の ID を読む") {
            val f = OrderFixture({ respond("""{"id":"ord-1","status":"PLACED"}""", HttpStatusCode.Created, JSON) })

            f.place() shouldBe Result.Ok(Placement.Accepted("ord-1"))

            val request = f.requests.single()
            request.url.toString() shouldBe "http://apisix:9080/sales/v1/orders"
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer t-1"
            request.headers["Idempotency-Key"] shouldBe KEY
            request.body.contentType.toString() shouldBe "application/json"
            request.body.toByteArray().decodeToString() shouldBe BODY.decodeToString()
        }

        test("400 と 422(検証の誤り)は、業務の誤りとして受け付けなかった") {
            OrderFixture({ respond("", HttpStatusCode.BadRequest) }).place() shouldBe Result.Ok(Placement.Refused(400))
            OrderFixture({ respond(problem("validation-failed"), HttpStatusCode.UnprocessableEntity, PROBLEM) }).place() shouldBe
                Result.Ok(Placement.Refused(422))
        }

        test("同じキーの違う要求(idempotency-key-reused)は、業務の誤りではなく設定の誤り(送信待ちのまま運用者が調べる)") {
            OrderFixture({ respond(problem("idempotency-key-reused"), HttpStatusCode.UnprocessableEntity, PROBLEM) }).place() shouldBe
                Result.Err(HttpOrderApi.IdempotencyKeyReused)
        }

        test("401 はトークンを取り直し、409(同じキーを処理中)と 503 はリトライする") {
            val f =
                OrderFixture(
                    { respond("", HttpStatusCode.Unauthorized) },
                    { respond(problem("idempotency-request-in-progress"), HttpStatusCode.Conflict, PROBLEM) },
                    { respond("""{"id":"ord-1"}""", HttpStatusCode.Created, JSON) },
                )

            f.place() shouldBe Result.Ok(Placement.Accepted("ord-1"))

            f.tokens shouldBe 2
            f.requests.map { it.headers[HttpHeaders.Authorization] } shouldContainExactly listOf("Bearer t-1", "Bearer t-2", "Bearer t-2")
        }

        test("リトライしきれない一時的な失敗と、403・500 は Result のエラー(送信待ちのまま残す)") {
            OrderFixture(
                { respond("", HttpStatusCode.ServiceUnavailable) },
                { respond("", HttpStatusCode.ServiceUnavailable) },
                { respond("", HttpStatusCode.ServiceUnavailable) },
            ).place()
                .shouldBeInstanceOf<Result.Err<*>>()
                .error
                .shouldBeInstanceOf<HttpCallUnavailable>()
            OrderFixture({ respond("", HttpStatusCode.Forbidden) }).place() shouldBe Result.Err(HttpCallRejected(403))
            OrderFixture({ respond("", HttpStatusCode.InternalServerError) }).place() shouldBe Result.Err(HttpCallRejected(500))
        }

        test("201 の本文が契約と違えば、エラー") {
            OrderFixture({ respond("""{"status":"PLACED"}""", HttpStatusCode.Created, JSON) }).place().shouldBeInstanceOf<Result.Err<*>>()
        }
    })
