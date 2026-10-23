package io.eia.batchetl.adapters.http

import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DailySummary
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
import io.kotest.assertions.fail
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

class HttpOrderSummarySourceSpec :
    FunSpec({
        val day =
            when (val parsed = BusinessDay.parse("2026-10-13")) {
                is Result.Ok -> parsed.value
                is Result.Err -> fail("$parsed")
            }
        val digest = "0123456789abcdef".repeat(4)
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        fun summaryBody(
            timeZone: String = "Asia/Tokyo",
            date: String = "2026-10-13",
            count: Long = 3,
            hash: String = digest,
        ) = """{"date":"$date","timeZone":"$timeZone","from":"2026-10-12T15:00:00Z","until":"2026-10-13T15:00:00Z",""" +
            """"orderCount":$count,"digest":"$hash"}"""

        class Fixture(
            summaries: MutableList<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>,
        ) {
            val requests = mutableListOf<HttpRequestData>()
            var tokens = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/token")) {
                            tokens++
                            respond(
                                """{"access_token":"t-$tokens","token_type":"Bearer","expires_in":300,"scope":"sales.order:report"}""",
                                HttpStatusCode.OK,
                                jsonHeaders,
                            )
                        } else {
                            requests += request
                            summaries.removeFirst()(request)
                        }
                    },
                )
            val source =
                HttpOrderSummarySource(
                    client,
                    URI("http://apisix:9080/"),
                    ClientCredentialsTokenProvider(
                        ClientCredentialsConfig(
                            URI("http://keycloak:8080/realms/eiaf/protocol/openid-connect/token"),
                            "eiaf-batch-etl",
                            SecretName("BATCH_ETL_CLIENT_SECRET"),
                        ),
                        client,
                        EnvSecretProvider(mapOf("BATCH_ETL_CLIENT_SECRET" to "secret")),
                    ),
                    Resilience(
                        "order-service",
                        ResilienceConfig(
                            attemptTimeout = 5.seconds,
                            retry = RetryPolicy(initialDelay = 1.milliseconds, maxDelay = 1.milliseconds),
                        ),
                    ),
                )
        }

        test("API Gateway の公開のパスに、Client Credentials のトークンを付けて問い合わせ、件数とハッシュを読む") {
            val f = Fixture(mutableListOf({ respond(summaryBody(), HttpStatusCode.OK, jsonHeaders) }))

            f.source.summary(day) shouldBe Result.Ok(DailySummary(day, 3, digest))

            val request = f.requests.single()
            request.url.toString() shouldBe "http://apisix:9080/sales/v1/order-summaries/daily/2026-10-13"
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer t-1"
        }

        test("401 ならトークンを取り直してリトライし、503 もリトライする") {
            val f =
                Fixture(
                    mutableListOf(
                        { respond("", HttpStatusCode.Unauthorized) },
                        { respond("", HttpStatusCode.ServiceUnavailable) },
                        { respond(summaryBody(), HttpStatusCode.OK, jsonHeaders) },
                    ),
                )

            f.source.summary(day).shouldBeInstanceOf<Result.Ok<DailySummary>>()

            f.tokens shouldBe 2
            f.requests.map { it.headers[HttpHeaders.Authorization] } shouldBe listOf("Bearer t-1", "Bearer t-2", "Bearer t-2")
        }

        test("403(スコープがない)はリトライしない") {
            val f = Fixture(mutableListOf({ respond("", HttpStatusCode.Forbidden) }))

            f.source
                .summary(day)
                .shouldBeInstanceOf<Result.Err<DomainError>>()
                .error shouldBe HttpCallRejected(403)
            f.requests.size shouldBe 1
        }

        test("区切り・日付・件数・ハッシュ・形式が契約と違えば、比べずに失敗にする(リトライしない)") {
            listOf(
                summaryBody(timeZone = "UTC"),
                summaryBody(date = "2026-10-14"),
                summaryBody(count = -1),
                summaryBody(hash = "XYZ"),
                """{"date":"2026-10-13"}""",
                "not json",
            ).forEach { body ->
                val f = Fixture(mutableListOf({ respond(body, HttpStatusCode.OK, jsonHeaders) }))
                f.source
                    .summary(day)
                    .shouldBeInstanceOf<Result.Err<DomainError>>()
                    .error
                    .shouldBeInstanceOf<UnexpectedError>()
                f.requests.size shouldBe 1
            }
        }
    })
