package io.eia.platform.security.token

import io.eia.platform.security.jwt.MutableClock
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

// JVM の部品をつないだ ClientCredentialsTokenProvider のテストの部品。取得・キャッシュ・分類の振る舞いは
// shared/integration-sdk/http の ClientCredentialsTokenProviderSpec で検査する(ADR-0037)。

internal const val CLIENT_ID = "eiaf-e2e"
internal const val CLIENT_SECRET = "s3cr3t-value-for-test"
internal val SECRET_NAME = SecretName("EIAF_E2E_CLIENT_SECRET")
private val TOKEN_ENDPOINT = URI.create("http://idp.test/realms/eiaf/protocol/openid-connect/token")

internal fun tokenJson(
    token: String = "at-1",
    expiresIn: Long? = 300,
    type: String = "Bearer",
): String =
    buildString {
        append("""{"access_token":"$token","token_type":"$type"""")
        expiresIn?.let { append(""","expires_in":$it""") }
        append("}")
    }

/** トークンエンドポイントの代わり。受けた要求を記録し、[responses] の順に応答する(尽きたら最後の応答を繰り返す)。 */
internal class FakeTokenEndpoint(
    vararg responses: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val queue = responses.toMutableList()

    val client =
        HttpClient(
            MockEngine { request ->
                requests += request
                val next = if (queue.size > 1) queue.removeAt(0) else queue.single()
                next(request)
            },
        )

    companion object {
        fun ok(json: String = tokenJson()): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

        fun status(
            status: HttpStatusCode,
            body: String = "",
        ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond(body, status) }
    }
}

/** 1 回だけ試行する [Resilience](リトライ・Circuit Breaker なし。要求の回数をリトライに左右されないようにする)。 */
private fun singleAttempt(): Resilience =
    Resilience(
        ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME,
        ResilienceConfig(attemptTimeout = 5.seconds, retry = null, retryBudget = null, circuitBreaker = null),
    )

internal fun provider(
    endpoint: FakeTokenEndpoint,
    clock: MutableClock = MutableClock(),
    secrets: SecretProvider = SecretProvider { ok(Secret(CLIENT_SECRET)) },
): ClientCredentialsTokenProvider =
    ClientCredentialsTokenProvider(
        ClientCredentialsConfig(TOKEN_ENDPOINT, CLIENT_ID, SECRET_NAME),
        endpoint.client,
        secrets,
        clock,
        singleAttempt(),
    )
