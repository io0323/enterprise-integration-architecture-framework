package io.eia.platform.reliability

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val FIXED_CLOCK =
    object : Clock {
        override fun now() = NOW
    }

private val classifier = HttpCallClassifier(clock = FIXED_CLOCK)

/** [status] と [headers] を返すクライアント。 */
private fun respondWith(
    status: HttpStatusCode,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): HttpClient =
    HttpClient(
        MockEngine {
            respond(
                body,
                status,
                io.ktor.http.Headers
                    .build { headers.forEach { (k, v) -> append(k, v) } },
            )
        },
    )

private suspend fun HttpClient.classified(): Result<String, DomainError> =
    classifier.call(send = { get("http://inventory.test/v1/items") { expectSuccess = false } }) { ok(it.bodyAsText()) }

private fun Result<*, DomainError>.error(): DomainError = shouldBeInstanceOf<Result.Err<DomainError>>().error

class HttpCallClassifierSpec :
    FunSpec({
        context("ステータス(INTEGRATION_STANDARDS §3)") {
            test("2xx は read の結果を返す") {
                respondWith(HttpStatusCode.OK, body = "stock").classified() shouldBe ok("stock")
            }

            test("408・429・502・503・504 は Retryable") {
                listOf(408, 429, 502, 503, 504).forEach { status ->
                    val error = respondWith(HttpStatusCode.fromValue(status)).classified().error()
                    error shouldBe HttpCallUnavailable(status, "status")
                    error.shouldBeInstanceOf<DomainError.Retryable>()
                }
            }

            test("429 と 503 は Retry-After(秒数と HTTP-date)を retryAfter に入れる") {
                respondWith(HttpStatusCode.TooManyRequests, mapOf("Retry-After" to "7")).classified().error() shouldBe
                    HttpCallUnavailable(429, "status", 7.seconds)
                val dated = respondWith(HttpStatusCode.ServiceUnavailable, mapOf("Retry-After" to httpDate(NOW + 90.seconds)))
                dated.classified().error() shouldBe HttpCallUnavailable(503, "status", 90.seconds)
            }

            test("429・503 以外の Retry-After は使わない") {
                respondWith(HttpStatusCode.BadGateway, mapOf("Retry-After" to "7")).classified().error() shouldBe
                    HttpCallUnavailable(502, "status")
            }

            test("500 とそのほかの 4xx は NonRetryable") {
                listOf(400, 401, 403, 404, 409, 422, 500, 501).forEach { status ->
                    val error = respondWith(HttpStatusCode.fromValue(status)).classified().error()
                    error shouldBe HttpCallRejected(status)
                    error.shouldBeInstanceOf<DomainError.NonRetryable>()
                }
            }

            test("依存先ごとに 500 を Retryable にできる") {
                val lenient = HttpCallClassifier(HttpCallClassifier.DEFAULT_RETRYABLE_STATUSES + 500, FIXED_CLOCK)
                lenient.classify(HttpStatusCode.InternalServerError, null) shouldBe HttpCallUnavailable(500, "status")
            }

            test("2xx を Retryable にする設定と、2xx の分類は拒否する") {
                shouldThrow<IllegalArgumentException> { HttpCallClassifier(setOf(204)) }
                shouldThrow<IllegalArgumentException> { classifier.classify(HttpStatusCode.OK, null) }
            }

            test("expectSuccess = true の ResponseException も、その応答で分類する") {
                val client = respondWith(HttpStatusCode.ServiceUnavailable, mapOf("Retry-After" to "3"))
                classifier
                    .call(send = { client.get("http://inventory.test/v1/items") { expectSuccess = true } }) { ok(it.bodyAsText()) }
                    .error() shouldBe HttpCallUnavailable(503, "status", 3.seconds)
            }
        }

        context("例外") {
            test("IOException と名前解決の失敗は Retryable の connection。例外のメッセージは使わない") {
                listOf(IOException("connect to http://secret.test?token=x failed"), UnresolvedAddressException()).forEach { failure ->
                    val error = classifier.call<String>(send = { throw failure }) { ok("never") }.error()
                    error shouldBe HttpCallUnavailable(null, "connection")
                    error.message shouldBe "依存先が一時的に使えません(connection)"
                }
            }

            test("本文の読み取り中の切断も Retryable") {
                val client = respondWith(HttpStatusCode.OK)
                classifier
                    .call<String>(send = { client.get("http://inventory.test/v1/items") }) { throw IOException("reset") }
                    .error() shouldBe HttpCallUnavailable(null, "connection")
            }

            test("キャンセルとそのほかの例外は捕まえずに伝える") {
                shouldThrow<CancellationException> { classifier.call<String>(send = { throw CancellationException("stop") }) { ok("") } }
                shouldThrow<IllegalStateException> { classifier.call<String>(send = { error("bug") }) { ok("") } }
            }

            test("実際の接続: 閉じたポートは connection") {
                val port = ServerSocket(0).use { it.localPort }
                HttpClient(CIO).use { client ->
                    classifier
                        .call(send = { client.get("http://127.0.0.1:$port/") { expectSuccess = false } }) { ok(it.bodyAsText()) }
                        .error() shouldBe HttpCallUnavailable(null, "connection")
                }
            }

            test("実際の接続: 応答しないサーバへの Ktor の HttpTimeout は timeout(キャンセルとして伝えない)") {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                    HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 200 } }.use { client ->
                        classifier
                            .call(send = { client.get("http://127.0.0.1:${server.localPort}/") }) { ok(it.bodyAsText()) }
                            .error() shouldBe HttpCallUnavailable(null, "timeout")
                    }
                }
            }
        }

        context("Resilience と組み合わせる") {
            val policy = RetryPolicy(initialDelay = 10.milliseconds, maxAttempts = 3, jitter = Jitter.NONE)
            val config = ResilienceConfig(attemptTimeout = 1.seconds, retry = policy, retryBudget = null)

            test("Retryable はリトライし、NonRetryable はリトライしない") {
                var calls = 0
                val flaky =
                    HttpClient(
                        MockEngine {
                            calls++
                            if (calls < 3) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
                        },
                    )
                Resilience("inventory", config).execute { flaky.classified() } shouldBe ok("ok")
                calls shouldBe 3

                var rejected = 0
                val badRequest =
                    HttpClient(
                        MockEngine {
                            rejected++
                            respond("", HttpStatusCode.BadRequest, headersOf())
                        },
                    )
                Resilience("inventory", config).execute { badRequest.classified() }.error() shouldBe HttpCallRejected(400)
                rejected shouldBe 1
            }
        }
    })
