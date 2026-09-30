package io.eia.platform.security.token

import io.eia.platform.security.jwt.MutableClock
import io.eia.platform.security.jwt.NOW
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretNotFound
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.security.secret.SecretUnreadable
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.CircuitState
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.ResilienceListener
import io.eia.shared.resilience.RetrySuppression
import io.eia.shared.resilience.SlidingWindow
import io.eia.shared.resilience.withCallDeadline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
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
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URI
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaInstant

internal const val CLIENT_ID = "eiaf-e2e"
internal const val CLIENT_SECRET = "s3cr3t-value-for-test"
internal val SECRET_NAME = SecretName("EIAF_E2E_CLIENT_SECRET")
private val TOKEN_ENDPOINT = URI.create("http://idp.test/realms/eiaf/protocol/openid-connect/token")

internal fun tokenJson(
    token: String = "at-1",
    expiresIn: Long? = 300,
    type: String = "Bearer",
    scope: String? = null,
): String =
    buildString {
        append("""{"access_token":"$token","token_type":"$type"""")
        expiresIn?.let { append(""","expires_in":$it""") }
        scope?.let { append(""","scope":"$it"""") }
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
            headers: Map<String, String> = emptyMap(),
        ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            {
                respond(
                    body,
                    status,
                    io.ktor.http.Headers
                        .build { headers.forEach { (k, v) -> append(k, v) } },
                )
            }
    }
}

internal fun tokenConfig(
    scopes: Set<String> = emptySet(),
    clientId: String = CLIENT_ID,
): ClientCredentialsConfig = ClientCredentialsConfig(TOKEN_ENDPOINT, clientId, SECRET_NAME, scopes = scopes)

/**
 * 1 回だけ試行する [Resilience](リトライ・Circuit Breaker・リトライバジェットなし)。
 * キャッシュ・分類・要求の形式のテストで、要求の回数をリトライに左右されないようにする。
 */
internal fun singleAttempt(attemptTimeout: Duration = 5.seconds): Resilience =
    Resilience(
        ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME,
        ResilienceConfig(attemptTimeout = attemptTimeout, retry = null, retryBudget = null, circuitBreaker = null),
    )

internal fun provider(
    endpoint: FakeTokenEndpoint,
    clock: MutableClock = MutableClock(),
    secrets: SecretProvider = SecretProvider { ok(Secret(CLIENT_SECRET)) },
    config: ClientCredentialsConfig = tokenConfig(),
    resilience: Resilience = singleAttempt(),
): ClientCredentialsTokenProvider = ClientCredentialsTokenProvider(config, endpoint.client, secrets, clock, resilience)

/** 待ち時間を固定した RetryPolicy(10ms → 20ms)。Retry-After があればそれを優先する。 */
private val FAST_RETRY = RetryPolicy(initialDelay = 10.milliseconds, maxAttempts = 3, jitter = Jitter.NONE)

/** リトライと見送りを記録する。 */
private class RecordingListener : ResilienceListener {
    val retryDelays = CopyOnWriteArrayList<Duration>()
    val suppressions = CopyOnWriteArrayList<RetrySuppression>()

    override fun onRetry(
        name: String,
        attempt: Int,
        delay: Duration,
        error: DomainError,
    ) {
        retryDelays += delay
    }

    override fun onRetrySuppressed(
        name: String,
        reason: RetrySuppression,
    ) {
        suppressions += reason
    }
}

private fun retrying(
    listener: ResilienceListener = ResilienceListener.NONE,
    deadline: Duration? = 10.seconds,
    circuitBreaker: CircuitBreakerConfig? = null,
    retry: RetryPolicy? = FAST_RETRY,
    attemptTimeout: Duration = 5.seconds,
): Resilience =
    Resilience(
        ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME,
        ResilienceConfig(
            attemptTimeout = attemptTimeout,
            deadline = deadline,
            retry = retry,
            retryBudget = null,
            circuitBreaker = circuitBreaker,
        ),
        listener = listener,
    )

private fun Result<AccessToken, TokenError>.token(): String = shouldBeInstanceOf<Result.Ok<AccessToken>>().value.reveal()

private fun Result<AccessToken, TokenError>.error(): TokenError = shouldBeInstanceOf<Result.Err<TokenError>>().error

private suspend fun HttpRequestData.form(): Map<String, String> =
    parseQueryString(body.toByteArray().decodeToString()).entries().associate { it.key to it.value.single() }

class ClientCredentialsTokenProviderSpec :
    FunSpec({
        context("キャッシュ") {
            test("期限の 30 秒前までは同じトークンを返し、要求しない") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson("at-1")), FakeTokenEndpoint.ok(tokenJson("at-2")))
                val clock = MutableClock()
                val provider = provider(endpoint, clock)

                provider.token().token() shouldBe "at-1"
                clock.now = NOW + 269.seconds
                provider.token().token() shouldBe "at-1"
                endpoint.requests.size shouldBe 1

                // 期限(300 秒)の 30 秒前を過ぎたら取り直す
                clock.now = NOW + 270.seconds
                provider.token().token() shouldBe "at-2"
                endpoint.requests.size shouldBe 2
            }

            test("寿命が短いトークンは、寿命の 10% 前に取り直す(60 秒なら 6 秒前)") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson("at-1", 60)), FakeTokenEndpoint.ok(tokenJson("at-2", 60)))
                val clock = MutableClock()
                val provider = provider(endpoint, clock)

                provider.token().token() shouldBe "at-1"
                clock.now = NOW + 53.seconds
                provider.token().token() shouldBe "at-1"
                clock.now = NOW + 54.seconds
                provider.token().token() shouldBe "at-2"
            }

            test("expires_in が長くても、maxCacheLifetime(既定 1 時間)で取り直す") {
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson("at-1", expiresIn = 86_400)), FakeTokenEndpoint.ok(tokenJson("at-2")))
                val clock = MutableClock()
                val provider = provider(endpoint, clock)

                provider.token().token() shouldBe "at-1"
                clock.now = NOW + 59.minutes + 29.seconds
                provider.token().token() shouldBe "at-1"
                clock.now = NOW + 59.minutes + 30.seconds
                provider.token().token() shouldBe "at-2"
            }

            test("maxCacheLifetime を過ぎたら、取り直しに失敗しても長い expires_in のトークンを使い続けない") {
                val endpoint =
                    FakeTokenEndpoint(
                        FakeTokenEndpoint.ok(tokenJson("at-1", expiresIn = 86_400)),
                        FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable),
                    )
                val clock = MutableClock()
                val provider = provider(endpoint, clock)
                provider.token()

                // 打ち切った期限(1 時間)の前なら、取り直しに失敗しても期限内のトークンを使い続ける
                clock.now = NOW + 59.minutes + 40.seconds
                provider.token().token() shouldBe "at-1"

                // 打ち切った期限を過ぎたら、IdP の expires_in(24 時間)の中でも失敗を返す
                clock.now = NOW + 60.minutes
                provider.token().error() shouldBe TokenEndpointUnavailable("server_error", 503)
            }

            test("expires_in のない応答はキャッシュしない(期限が分からないため)") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson(expiresIn = null)))
                val provider = provider(endpoint)

                provider
                    .token()
                    .shouldBeInstanceOf<Result.Ok<AccessToken>>()
                    .value.expiresAt shouldBe null
                provider.token()
                endpoint.requests.size shouldBe 2
            }

            test("invalidate したトークンは次の呼び出しで取り直す。別のトークンに替わっていれば何もしない") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson("at-1")), FakeTokenEndpoint.ok(tokenJson("at-2")))
                val provider = provider(endpoint)
                val first = (provider.token() as Result.Ok).value

                provider.invalidate(first)
                val second = (provider.token() as Result.Ok).value
                second.reveal() shouldBe "at-2"

                provider.invalidate(first)
                provider.token().shouldBeInstanceOf<Result.Ok<AccessToken>>().value shouldBeSameInstanceAs second
                endpoint.requests.size shouldBe 2
            }
        }

        context("同時の取得") {
            test("100 並列で呼んでも、要求は 1 回にまとめる") {
                val release = CompletableDeferred<Unit>()
                val endpoint =
                    FakeTokenEndpoint({
                        release.await()
                        respond(tokenJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                val provider = provider(endpoint)

                val results =
                    coroutineScope {
                        val calls = (1..100).map { async { provider.token() } }
                        delay(100)
                        release.complete(Unit)
                        calls.awaitAll()
                    }

                results.forEach { it.token() shouldBe "at-1" }
                endpoint.requests.size shouldBe 1
            }

            test("取得が失敗したら、待っていた呼び出しは同じ失敗を受け取る(1 つずつ取り直して待たされない)") {
                val release = CompletableDeferred<Unit>()
                val endpoint =
                    FakeTokenEndpoint({
                        release.await()
                        respond("", HttpStatusCode.ServiceUnavailable)
                    })
                val provider = provider(endpoint)

                val results =
                    coroutineScope {
                        val calls = (1..20).map { async { provider.token() } }
                        delay(100)
                        release.complete(Unit)
                        calls.awaitAll()
                    }

                results.forEach { it.error() shouldBe TokenEndpointUnavailable("server_error", 503) }
                endpoint.requests.size shouldBe 1
            }

            test("期限前の取り直しの最中は、ほかの呼び出しを待たせずに期限内のトークンを返す") {
                val release = CompletableDeferred<Unit>()
                val endpoint =
                    FakeTokenEndpoint(
                        FakeTokenEndpoint.ok(tokenJson("at-1")),
                        {
                            release.await()
                            respond(tokenJson("at-2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    )
                val clock = MutableClock()
                val provider = provider(endpoint, clock)
                provider.token()
                clock.now = NOW + 280.seconds

                coroutineScope {
                    val refreshing = async { provider.token() }
                    delay(100)
                    provider.token().token() shouldBe "at-1"
                    release.complete(Unit)
                    refreshing.await().token() shouldBe "at-2"
                }
            }
        }

        context("呼び出し元の締め切り(ADR-0021 §12)") {
            test("取得は、呼び出し元の締め切りの残り時間で打ち切る(設定の締め切りより短ければ)") {
                val endpoint =
                    FakeTokenEndpoint({
                        delay(5.seconds)
                        respond(tokenJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                val provider = provider(endpoint, resilience = retrying(deadline = 10.seconds))
                val start = TimeSource.Monotonic.markNow()

                withCallDeadline(200.milliseconds) { provider.token() }.error() shouldBe TokenEndpointUnavailable("deadline_exceeded")
                (start.elapsedNow() < 2.seconds) shouldBe true
            }

            test("取得中の呼び出しを待つ時間も、残り時間までにする。待ちきれなかった後もロックは残らない") {
                val release = CompletableDeferred<Unit>()
                val endpoint =
                    FakeTokenEndpoint({
                        release.await()
                        respond(tokenJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                val provider = provider(endpoint)

                coroutineScope {
                    val leader = async { provider.token() }
                    delay(50)
                    val start = TimeSource.Monotonic.markNow()
                    withCallDeadline(100.milliseconds) { provider.token() }.error() shouldBe TokenEndpointUnavailable("deadline_exceeded")
                    (start.elapsedNow() < 2.seconds) shouldBe true

                    release.complete(Unit)
                    leader.await().token() shouldBe "at-1"
                }
                provider.token().token() shouldBe "at-1"
                endpoint.requests.size shouldBe 1
            }

            test("呼び出し元の締め切りで打ち切った失敗は、待っていた呼び出しに共有しない(自分の残り時間で取り直す)") {
                val endpoint =
                    FakeTokenEndpoint(
                        {
                            delay(5.seconds)
                            respond(tokenJson("at-1"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                        FakeTokenEndpoint.ok(tokenJson("at-2")),
                    )
                val provider = provider(endpoint)

                coroutineScope {
                    val leader = async { withCallDeadline(200.milliseconds) { provider.token() } }
                    delay(50)
                    val waiter = async { provider.token() }
                    leader.await().error() shouldBe TokenEndpointUnavailable("deadline_exceeded")
                    waiter.await().token() shouldBe "at-2"
                }
                endpoint.requests.size shouldBe 2
            }

            test("設定の締め切りで打ち切った失敗は、これまでどおり待っていた呼び出しに共有する(1 つずつ取り直して待たされない)") {
                val endpoint =
                    FakeTokenEndpoint({
                        delay(5.seconds)
                        respond(tokenJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                val provider = provider(endpoint, resilience = retrying(deadline = 200.milliseconds, retry = null))

                val results = coroutineScope { (1..5).map { async { provider.token() } }.awaitAll() }

                results.forEach { it.error() shouldBe TokenEndpointUnavailable("deadline_exceeded") }
                endpoint.requests.size shouldBe 1
            }
        }

        context("タイムアウトと失敗") {
            test("応答が timeout を超えたら打ち切り、Retryable の timeout にする") {
                val endpoint =
                    FakeTokenEndpoint({
                        delay(5.seconds)
                        respond(tokenJson(), HttpStatusCode.OK)
                    })
                val provider = provider(endpoint, resilience = singleAttempt(attemptTimeout = 200.milliseconds))

                val error = provider.token().error()
                error shouldBe TokenEndpointUnavailable("timeout")
                error.shouldBeInstanceOf<DomainError.Retryable>()
            }

            test("接続の失敗は Retryable の connection。例外のメッセージは使わない") {
                val endpoint = FakeTokenEndpoint({ throw IOException("connect to $CLIENT_SECRET failed") })

                provider(endpoint).token().error() shouldBe TokenEndpointUnavailable("connection")
            }

            test("接続の失敗でない例外(プログラムの誤り)は捕まえずに伝え、Circuit Breaker に数えない") {
                val endpoint = FakeTokenEndpoint({ error("bug") })
                val resilience = retrying(circuitBreaker = CircuitBreakerConfig(window = SlidingWindow.Count(1), minimumCalls = 1))

                shouldThrow<IllegalStateException> { provider(endpoint, resilience = resilience).token() }
                endpoint.requests.size shouldBe 1
                resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
            }

            test("期限前の取り直しに失敗したら、期限内のトークンを返し、refreshRetryInterval(5 秒)の間は取り直さない") {
                val endpoint =
                    FakeTokenEndpoint(
                        FakeTokenEndpoint.ok(tokenJson("at-1")),
                        FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable),
                        FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable),
                        FakeTokenEndpoint.ok(tokenJson("at-2")),
                    )
                val clock = MutableClock()
                val provider = provider(endpoint, clock)
                provider.token()

                clock.now = NOW + 280.seconds
                provider.token().token() shouldBe "at-1"
                clock.now = NOW + 284.seconds
                provider.token().token() shouldBe "at-1"
                endpoint.requests.size shouldBe 2

                clock.now = NOW + 285.seconds
                provider.token().token() shouldBe "at-1"
                endpoint.requests.size shouldBe 3

                clock.now = NOW + 290.seconds
                provider.token().token() shouldBe "at-2"
            }

            test("期限が切れた後の失敗は、キャッシュを使わずに失敗を返す") {
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson("at-1")), FakeTokenEndpoint.status(HttpStatusCode.BadGateway))
                val clock = MutableClock()
                val provider = provider(endpoint, clock)
                provider.token()

                clock.now = NOW + 300.seconds
                provider.token().error() shouldBe TokenEndpointUnavailable("server_error", 502)
            }
        }

        context("HTTP のステータスの分類") {
            test("429 は Retryable で、Retry-After(秒)を retryAfter に入れる") {
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "7")))

                val error = provider(endpoint).token().error()
                error shouldBe TokenEndpointUnavailable("rate_limited", 429, 7.seconds)
                (error as DomainError.Retryable).retryAfter shouldBe 7.seconds
            }

            test("Retry-After の HTTP-date は現在時刻からの差にする") {
                val date = DateTimeFormatter.RFC_1123_DATE_TIME.format((NOW + 90.seconds).toJavaInstant().atOffset(ZoneOffset.UTC))
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to date)))

                provider(endpoint).token().error() shouldBe TokenEndpointUnavailable("rate_limited", 429, 90.seconds)
            }

            test("503 は Retryable で Retry-After を入れる。500・408 も Retryable") {
                val unavailable =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable, headers = mapOf("Retry-After" to "3")))
                provider(unavailable).token().error() shouldBe TokenEndpointUnavailable("server_error", 503, 3.seconds)

                provider(FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.InternalServerError))).token().error() shouldBe
                    TokenEndpointUnavailable("server_error", 500)
                // 503 以外の 5xx の Retry-After は使わない(INTEGRATION_STANDARDS §3)
                val badGateway =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.BadGateway, headers = mapOf("Retry-After" to "3")))
                provider(badGateway).token().error() shouldBe TokenEndpointUnavailable("server_error", 502)
                provider(FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.RequestTimeout))).token().error() shouldBe
                    TokenEndpointUnavailable("request_timeout", 408)
            }

            test("400 / 401 は NonRetryable で、OAuth のエラーコードだけを取り出す") {
                val body = """{"error":"invalid_client","error_description":"Invalid client credentials for $CLIENT_ID"}"""
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.Unauthorized, body))

                val error = provider(endpoint).token().error()
                error shouldBe TokenRequestRejected(401, "invalid_client")
                error.shouldBeInstanceOf<DomainError.NonRetryable>()
            }

            test("エラーコードの形式が想定外なら取り出さない(本文の値をログに流さない)") {
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.BadRequest, """{"error":"Bad <script> $CLIENT_SECRET"}"""))

                provider(endpoint).token().error() shouldBe TokenRequestRejected(400, null)
            }
        }

        context("Retry と Circuit Breaker(ADR-0021)") {
            test("429 は Retry-After の時間だけ待ってからリトライする(バックオフより優先)") {
                val endpoint =
                    FakeTokenEndpoint(
                        FakeTokenEndpoint.status(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "1")),
                        FakeTokenEndpoint.ok(tokenJson("at-1")),
                    )
                val listener = RecordingListener()
                val provider = provider(endpoint, resilience = retrying(listener, retry = RetryPolicy.DEFAULT))

                provider.token().token() shouldBe "at-1"
                endpoint.requests.size shouldBe 2
                listener.retryDelays shouldContainExactly listOf(1.seconds)
            }

            test("Retry-After が RetryPolicy の上限(maxDelay)を超えるなら、待たずに打ち切る") {
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "60")))
                val provider = provider(endpoint, resilience = retrying(retry = RetryPolicy.DEFAULT))

                provider.token().error() shouldBe TokenEndpointUnavailable("rate_limited", 429, 60.seconds)
                endpoint.requests.size shouldBe 1
            }

            test("Retry-After が締め切りの残り時間を超えるなら、待たずに打ち切る") {
                val endpoint =
                    FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable, headers = mapOf("Retry-After" to "5")))
                val listener = RecordingListener()
                val provider = provider(endpoint, resilience = retrying(listener, deadline = 2.seconds))

                provider.token().error() shouldBe TokenEndpointUnavailable("server_error", 503, 5.seconds)
                endpoint.requests.size shouldBe 1
                listener.suppressions shouldContainExactly listOf(RetrySuppression.DEADLINE)
            }

            test("503・タイムアウト・接続の失敗はリトライする") {
                val endpoint =
                    FakeTokenEndpoint(
                        FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable),
                        {
                            delay(5.seconds)
                            respond(tokenJson(), HttpStatusCode.OK)
                        },
                        { throw IOException("reset") },
                        FakeTokenEndpoint.ok(tokenJson("at-1")),
                    )
                val provider =
                    provider(endpoint, resilience = retrying(retry = FAST_RETRY.copy(maxAttempts = 4), attemptTimeout = 200.milliseconds))

                provider.token().token() shouldBe "at-1"
                endpoint.requests.size shouldBe 4
            }

            test("400・401(invalid_client など)はリトライせず、Circuit Breaker の失敗にも数えない") {
                val circuitBreaker = CircuitBreakerConfig(window = SlidingWindow.Count(2), minimumCalls = 2)
                listOf(
                    HttpStatusCode.Unauthorized to """{"error":"invalid_client"}""",
                    HttpStatusCode.BadRequest to """{"error":"invalid_scope"}""",
                ).forEach { (status, body) ->
                    val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.status(status, body))
                    val resilience = retrying(circuitBreaker = circuitBreaker)
                    val provider = provider(endpoint, resilience = resilience)

                    repeat(3) { provider.token().error().shouldBeInstanceOf<TokenRequestRejected>() }
                    endpoint.requests.size shouldBe 3
                    resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
                }
            }

            test("1 つの Resilience を使い回し、複数回の取得にまたがって Circuit Breaker が開く。開いている間は IdP に要求を送らない") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable))
                val resilience =
                    retrying(
                        retry = null,
                        circuitBreaker = CircuitBreakerConfig(window = SlidingWindow.Count(4), minimumCalls = 4, openDuration = 30.seconds),
                    )
                val provider = provider(endpoint, resilience = resilience)

                // 1 回の取得は 1 回の失敗。4 回の取得で窓が埋まり、失敗率 100% で開く
                repeat(4) { provider.token().error() shouldBe TokenEndpointUnavailable("server_error", 503) }
                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN

                val rejected = provider.token().error().shouldBeInstanceOf<TokenEndpointUnavailable>()
                rejected.reason shouldBe "circuit_open"
                (rejected.retryAfter ?: Duration.ZERO).isPositive() shouldBe true
                endpoint.requests.size shouldBe 4
            }

            test("締め切りで打ち切った取得は deadline_exceeded で、リトライしない") {
                val endpoint =
                    FakeTokenEndpoint({
                        delay(5.seconds)
                        respond(tokenJson(), HttpStatusCode.OK)
                    })
                val provider = provider(endpoint, resilience = retrying(deadline = 300.milliseconds, attemptTimeout = 1.seconds))

                provider.token().error() shouldBe TokenEndpointUnavailable("deadline_exceeded")
                endpoint.requests.size shouldBe 1
            }

            test("既定の Resilience は、試行 5 秒・締め切り 10 秒・既定の RetryPolicy と Circuit Breaker") {
                with(ClientCredentialsTokenProvider.DEFAULT_RESILIENCE) {
                    attemptTimeout shouldBe 5.seconds
                    deadline shouldBe 10.seconds
                    retry shouldBe RetryPolicy.DEFAULT
                    circuitBreaker shouldBe CircuitBreakerConfig()
                }
            }
        }

        context("応答の検証") {
            test("access_token がない・Bearer でない・expires_in が不正・JSON でない応答を拒否する") {
                mapOf(
                    """{"token_type":"Bearer","expires_in":300}""" to "missing_access_token",
                    tokenJson(type = "mac") to "not_bearer",
                    tokenJson(expiresIn = -1) to "invalid_expires_in",
                    """{"access_token":"a","token_type":"Bearer","expires_in":"300s"}""" to "invalid_expires_in",
                    "<html>" to "not_json_object",
                    "[]" to "not_json_object",
                ).forEach { (body, reason) ->
                    provider(FakeTokenEndpoint(FakeTokenEndpoint.ok(body))).token().error() shouldBe InvalidTokenResponse(reason)
                }
            }

            test("大きすぎる応答は解析しない") {
                val huge = tokenJson(token = "a".repeat(ClientCredentialsTokenProvider.MAX_BODY_BYTES))
                provider(FakeTokenEndpoint(FakeTokenEndpoint.ok(huge))).token().error() shouldBe InvalidTokenResponse("body_too_large")
            }

            test("応答の scope を使い、なければ要求したスコープにする") {
                val withScope = FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson(scope = "sales.order:read")))
                provider(withScope, config = tokenConfig(scopes = setOf("sales.order:read", "sales.order:write")))
                    .token()
                    .shouldBeInstanceOf<Result.Ok<AccessToken>>()
                    .value.scopes shouldBe setOf("sales.order:read")

                val withoutScope = FakeTokenEndpoint(FakeTokenEndpoint.ok())
                provider(withoutScope, config = tokenConfig(scopes = setOf("sales.order:read")))
                    .token()
                    .shouldBeInstanceOf<Result.Ok<AccessToken>>()
                    .value.scopes shouldBe setOf("sales.order:read")
            }
        }

        context("要求の形式") {
            test("grant_type と scope(空白区切り)をフォームで送り、Secret は Authorization にだけ入れる") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok())
                provider(endpoint, config = tokenConfig(scopes = setOf("sales.order:write", "sales.order:read"))).token()

                val request = endpoint.requests.single()
                request.form() shouldBe mapOf("grant_type" to "client_credentials", "scope" to "sales.order:read sales.order:write")
                request.headers[HttpHeaders.Accept] shouldBe "application/json"
                request.url.toString() shouldBe TOKEN_ENDPOINT.toString()
            }

            test("スコープを指定しなければ scope を送らない") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok())
                provider(endpoint).token()

                endpoint.requests.single().form() shouldBe mapOf("grant_type" to "client_credentials")
            }

            test("client_secret_basic: 記号(: + / % 空白 ~ *)を含む ID と Secret を form-urlencoded してから Base64 にする(RFC 6749 §2.3.1)") {
                val secret = "a:b+c/d%e f~g*h=i&j"
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok())
                provider(endpoint, secrets = { ok(Secret(secret)) }, config = tokenConfig(clientId = "client:1")).token()

                val header = endpoint.requests.single().headers[HttpHeaders.Authorization]!!
                val decoded = Base64.getDecoder().decode(header.removePrefix("Basic ")).decodeToString()
                decoded shouldBe "client%3A1:a%3Ab%2Bc%2Fd%25e+f%7Eg*h%3Di%26j"
                // 受け取る側(IdP)は ':' で分けてから URL デコードする
                val (id, pass) = decoded.split(':')
                java.net.URLDecoder.decode(id, Charsets.UTF_8) shouldBe "client:1"
                java.net.URLDecoder.decode(pass, Charsets.UTF_8) shouldBe secret
            }

            test("Secret は取得のたびに SecretProvider から読む(ローテーションに追従する)") {
                var current = "first-secret"
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson(expiresIn = null)))
                val provider = provider(endpoint, secrets = { ok(Secret(current)) })

                provider.token()
                current = "second-secret"
                provider.token()

                val sent = endpoint.requests.map { it.headers[HttpHeaders.Authorization]!!.removePrefix("Basic ") }
                sent.map { Base64.getDecoder().decode(it).decodeToString() } shouldBe
                    listOf("$CLIENT_ID:first-secret", "$CLIENT_ID:second-secret")
            }

            test("Secret を取得できなければ要求せずに失敗する") {
                val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok())
                val provider = provider(endpoint, secrets = EnvSecretProvider(emptyMap()))

                provider.token().error() shouldBe ClientSecretUnavailable.Permanent(SecretNotFound(SECRET_NAME))
                endpoint.requests.size shouldBe 0
            }

            test("Secret の一時的な失敗は Retryable") {
                val provider = provider(FakeTokenEndpoint(FakeTokenEndpoint.ok()), secrets = { err(SecretUnreadable(SECRET_NAME, "io")) })

                provider
                    .token()
                    .error()
                    .shouldBeInstanceOf<ClientSecretUnavailable.Temporary>()
                    .shouldBeInstanceOf<DomainError.Retryable>()
            }
        }

        context("値を伏せる") {
            test("AccessToken の toString はトークンを出さない") {
                val token = AccessToken("eyJ.secret.token", NOW, setOf("a"))
                token.toString() shouldBe "AccessToken(***, expiresAt=$NOW, scopes=[a])"
                token.authorizationHeader() shouldBe "Bearer eyJ.secret.token"
            }
        }
    })
