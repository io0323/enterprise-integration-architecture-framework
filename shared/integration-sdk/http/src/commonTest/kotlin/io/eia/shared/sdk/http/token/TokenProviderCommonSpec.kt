package io.eia.shared.sdk.http.token

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val START = Instant.parse("2026-10-23T00:00:00Z")

private class SteppingClock(
    var now: Instant = START,
) : Clock {
    override fun now(): Instant = now
}

/**
 * KMP の共通のコードが、どのターゲット(jvm・js・linuxX64・macosArm64)でも同じに動くことの確認。
 * 振る舞いの詳細(同時の取得・締め切り・Circuit Breaker など)は jvmTest の ClientCredentialsTokenProviderSpec で検査する。
 */
class TokenProviderCommonSpec :
    FunSpec({
        val requests = mutableListOf<HttpRequestData>()
        val statuses = ArrayDeque<HttpStatusCode>()

        fun client() =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    when (val status = statuses.removeFirstOrNull() ?: HttpStatusCode.OK) {
                        HttpStatusCode.OK -> {
                            respond(
                                """{"access_token":"at-${requests.size}","token_type":"Bearer","expires_in":300}""",
                                status,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond("", status, headersOf(HttpHeaders.RetryAfter, "7"))
                        }
                    }
                },
            )

        fun provider(clock: Clock) =
            ClientCredentialsTokenProvider(
                config = ClientCredentialsConfig(Url("http://idp.test/token"), "client:1", scopes = setOf("a")),
                httpClient = client(),
                secrets = { ok(ClientSecret("p@ss word")) },
                clock = clock,
                resilience = Resilience("test", ResilienceConfig(attemptTimeout = 5.seconds, retry = null, circuitBreaker = null)),
            )

        beforeTest {
            requests.clear()
            statuses.clear()
        }

        test("期限の 30 秒前までは同じトークンを返し、その後に取り直す") {
            val clock = SteppingClock()
            val provider = provider(clock)

            provider
                .token()
                .shouldBeInstanceOf<Result.Ok<AccessToken>>()
                .value
                .reveal() shouldBe "at-1"
            clock.now = START + 269.seconds
            provider
                .token()
                .shouldBeInstanceOf<Result.Ok<AccessToken>>()
                .value
                .reveal() shouldBe "at-1"
            clock.now = START + 270.seconds
            provider
                .token()
                .shouldBeInstanceOf<Result.Ok<AccessToken>>()
                .value
                .reveal() shouldBe "at-2"
            requests.size shouldBe 2
        }

        test("client_secret_basic: ID と Secret を form-urlencoded してから Base64 にする") {
            provider(SteppingClock()).token()

            val header = requests.single().headers[HttpHeaders.Authorization].orEmpty()
            Base64.decode(header.removePrefix("Basic ")).decodeToString() shouldBe "client%3A1:p%40ss+word"
        }

        test("429 は Retryable で、Retry-After を retryAfter に入れる") {
            statuses += HttpStatusCode.TooManyRequests

            provider(SteppingClock()).token().shouldBeInstanceOf<Result.Err<TokenError>>().error shouldBe
                TokenEndpointUnavailable("rate_limited", 429, 7.seconds)
        }
    })
