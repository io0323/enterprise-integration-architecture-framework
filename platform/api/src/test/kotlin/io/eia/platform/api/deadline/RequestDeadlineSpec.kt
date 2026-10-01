package io.eia.platform.api.deadline

import io.eia.platform.api.problem.installProblemDetails
import io.eia.shared.resilience.CallDeadline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RequestDeadlineSpec :
    FunSpec({
        test("ハンドラは予算の残り時間を CallDeadline で受け取る") {
            testApplication {
                application {
                    installRequestDeadline(2.seconds)
                    routing {
                        get("/remaining") {
                            call.respondText(
                                CallDeadline
                                    .current()
                                    ?.remaining()
                                    ?.inWholeMilliseconds
                                    .toString(),
                            )
                        }
                    }
                }
                val remaining = client.get("/remaining").bodyAsText().toLong()
                remaining shouldBeLessThanOrEqual 2_000
                remaining shouldBeGreaterThan 1_000
            }
        }

        test("予算を超えた処理は打ち切り、503 deadline-exceeded(Problem Details・Retry-After)を返す") {
            testApplication {
                application {
                    installRequestDeadline(200.milliseconds)
                    installProblemDetails()
                    routing { get("/slow") { delay(5_000).also { call.respondText("late") } } }
                }
                val response = client.get("/slow")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.headers[HttpHeaders.ContentType] shouldBe "application/problem+json"
                response.headers[HttpHeaders.RetryAfter] shouldBe "1"
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["type"]?.jsonPrimitive?.content shouldBe "https://eiaf.example/problems/deadline-exceeded"
                body["status"]?.jsonPrimitive?.int shouldBe 503
            }
        }

        test("Retry-After は設定できる") {
            testApplication {
                application {
                    installRequestDeadline(100.milliseconds, retryAfter = 3.seconds)
                    routing { get("/slow") { delay(5_000).also { call.respondText("late") } } }
                }
                client.get("/slow").headers[HttpHeaders.RetryAfter] shouldBe "3"
            }
        }

        test("処理の中の別の withTimeout の期限切れは、予算切れとして扱わない(例外として伝わり 504)") {
            testApplication {
                application {
                    installRequestDeadline(5.seconds)
                    routing { get("/inner") { withTimeout(20) { delay(5_000) }.also { call.respondText("late") } } }
                }
                client.get("/inner").status shouldBe HttpStatusCode.GatewayTimeout
            }
        }

        test("予算内に終わった処理はそのまま返す") {
            testApplication {
                application {
                    installRequestDeadline(2.seconds)
                    routing { get("/fast") { call.respondText("ok") } }
                }
                val response = client.get("/fast")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "ok"
            }
        }

        test("予算は正の値で、Retry-After は 0 以上") {
            testApplication {
                application {
                    shouldThrow<IllegalArgumentException> { installRequestDeadline(0.milliseconds) }
                    shouldThrow<IllegalArgumentException> { installRequestDeadline(1.seconds, retryAfter = (-1).seconds) }
                }
                client.get("/")
            }
        }
    })
