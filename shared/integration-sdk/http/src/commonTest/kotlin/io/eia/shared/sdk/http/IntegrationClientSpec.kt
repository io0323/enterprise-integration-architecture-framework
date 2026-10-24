package io.eia.shared.sdk.http

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.IdempotencyKey
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.trace.TraceParent
import io.eia.shared.sdk.http.token.ClientCredentialsConfig
import io.eia.shared.sdk.http.token.ClientCredentialsTokenProvider
import io.eia.shared.sdk.http.token.ClientSecret
import io.eia.shared.sdk.http.token.TokenEndpointUnavailable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ORDERS = "http://gateway.test/sales"

/** 応答を順に返す依存先(尽きたら最後の応答を繰り返す)。トークンエンドポイント(`/token`)は別に数える。 */
private class FakeServer(
    vararg statuses: HttpStatusCode,
    private val headers: Map<String, String> = emptyMap(),
) {
    val requests = mutableListOf<HttpRequestData>()
    val tokenRequests = mutableListOf<HttpRequestData>()
    private val queue = statuses.toMutableList()

    val client =
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/token") {
                    tokenRequests += request
                    respond(
                        """{"access_token":"at-${tokenRequests.size}","token_type":"Bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    requests += request
                    val status = if (queue.size > 1) queue.removeAt(0) else queue.single()
                    respond("""{"ok":true}""", status, headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()))
                }
            },
        )
}

private fun resilience(maxAttempts: Int = 3) =
    Resilience(
        "orders",
        ResilienceConfig(
            attemptTimeout = 5.seconds,
            retry = RetryPolicy(initialDelay = 10.milliseconds, maxAttempts = maxAttempts, jitter = Jitter.NONE),
            retryBudget = null,
            circuitBreaker = null,
        ),
    )

private fun FakeServer.integrationClient(withTokens: Boolean = true) =
    IntegrationClient(
        baseUrl = Url(ORDERS),
        resilience = resilience(),
        tokens =
            if (withTokens) {
                ClientCredentialsTokenProvider(
                    config = ClientCredentialsConfig(Url("http://idp.test/token"), "client"),
                    httpClient = client,
                    secrets = { ok(ClientSecret("secret")) },
                )
            } else {
                null
            },
        httpClient = client,
        random = Random(42),
    )

private val readText: suspend (io.ktor.client.statement.HttpResponse) -> Result<String, DomainError> = { ok(it.bodyAsText()) }

class IntegrationClientSpec :
    FunSpec({
        test("Gateway の公開 URL に path をつなぎ、Bearer のトークンを付ける") {
            val server = FakeServer(HttpStatusCode.OK)

            server.integrationClient().get("/v1/orders/o-1", read = readText).getOrNull() shouldBe """{"ok":true}"""

            val request = server.requests.single()
            request.url.toString() shouldBe "$ORDERS/v1/orders/o-1"
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer at-1"
        }

        test("呼び出し元の traceparent の子と Correlation ID を送る。リトライでも同じ値") {
            val server = FakeServer(HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK)
            val parent = TraceParent.generate(Random(1))
            val correlationId = CorrelationId.generate(Random(2))

            server.integrationClient().get("/v1/orders", CallContext(parent, correlationId), readText).getOrNull() shouldNotBe null

            server.requests.size shouldBe 2
            val sent = server.requests.map { it.headers["traceparent"] }.toSet()
            sent.size shouldBe 1
            val child = (TraceParent.parse(sent.single()!!) as Result.Ok).value
            child.traceId shouldBe parent.traceId
            child.parentId shouldNotBe parent.parentId
            server.requests.map { it.headers["X-Correlation-Id"] }.toSet() shouldBe setOf(correlationId.value)
        }

        test("文脈がなければ新しいトレースを始め、Correlation ID を採番する") {
            val server = FakeServer(HttpStatusCode.OK)

            server.integrationClient().get("/v1/orders", read = readText)

            val request = server.requests.single()
            TraceParent.parse(request.headers["traceparent"]!!).shouldBeInstanceOf<Result.Ok<TraceParent>>()
            CorrelationId.parse(request.headers["X-Correlation-Id"]!!).shouldBeInstanceOf<Result.Ok<CorrelationId>>()
        }

        test("POST は Idempotency-Key と本文を送り、リトライでも同じキーを送る") {
            val server = FakeServer(HttpStatusCode.BadGateway, HttpStatusCode.Created)
            val key = IdempotencyKey.generate(Random(3))

            server
                .integrationClient()
                .post("/v1/orders", """{"sku":"A"}""", ContentType.Application.Json, key, read = readText)
                .getOrNull() shouldNotBe null

            server.requests.map { it.headers["Idempotency-Key"] } shouldBe listOf(key.value, key.value)
            server.requests.map { it.body.toByteArray().decodeToString() }.toSet() shouldBe setOf("""{"sku":"A"}""")
            server.requests
                .first()
                .body.contentType shouldBe ContentType.Application.Json
        }

        test("429 は Retry-After の時間だけ待ってリトライし、尽きたら Retryable の失敗を返す") {
            val server = FakeServer(HttpStatusCode.TooManyRequests, headers = mapOf(HttpHeaders.RetryAfter to "0"))

            val result = server.integrationClient().get("/v1/orders", read = readText)

            server.requests.size shouldBe 3
            result.shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe HttpCallUnavailable(429, "status", 0.seconds)
        }

        test("401 なら、トークンを捨てて 1 回だけ取り直して送り直す") {
            val server = FakeServer(HttpStatusCode.Unauthorized, HttpStatusCode.OK)

            server.integrationClient().get("/v1/orders", read = readText).getOrNull() shouldNotBe null

            server.tokenRequests.size shouldBe 2
            server.requests.map { it.headers[HttpHeaders.Authorization] } shouldBe listOf("Bearer at-1", "Bearer at-2")
        }

        test("取り直した後も 401 なら、NonRetryable の失敗を返す(繰り返さない)") {
            val server = FakeServer(HttpStatusCode.Unauthorized)

            val result = server.integrationClient().get("/v1/orders", read = readText)

            result.shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe HttpCallRejected(401)
            server.requests.size shouldBe 2
            server.tokenRequests.size shouldBe 2
        }

        test("トークンを取得できなければ、要求を送らずにその失敗を返す") {
            val server = FakeServer(HttpStatusCode.OK)
            val failing =
                IntegrationClient(
                    baseUrl = Url(ORDERS),
                    resilience = resilience(maxAttempts = 1),
                    tokens =
                        ClientCredentialsTokenProvider(
                            config = ClientCredentialsConfig(Url("http://idp.test/token"), "client"),
                            httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }),
                            secrets = { ok(ClientSecret("secret")) },
                            resilience = resilience(maxAttempts = 1),
                        ),
                    httpClient = server.client,
                )

            failing.get("/v1/orders", read = readText).shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe
                TokenEndpointUnavailable("server_error", 503)
            server.requests.size shouldBe 0
        }

        test("トークンなし(tokens = null)では Authorization を付けない") {
            val server = FakeServer(HttpStatusCode.OK)

            server.integrationClient(withTokens = false).get("/v1/orders", read = readText)

            server.requests.single().headers[HttpHeaders.Authorization] shouldBe null
        }
    })
