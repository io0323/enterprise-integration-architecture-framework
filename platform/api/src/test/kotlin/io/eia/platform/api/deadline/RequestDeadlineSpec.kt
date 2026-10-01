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
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
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

        test("予算を超えた処理は打ち切って 504 を返す(Problem Details の例外の扱いより外側でも内側でも)") {
            testApplication {
                application {
                    installRequestDeadline(200.milliseconds)
                    installProblemDetails()
                    routing { get("/slow") { delay(5_000).also { call.respondText("late") } } }
                }
                client.get("/slow").status shouldBe HttpStatusCode.GatewayTimeout
            }
        }

        test("予算は正の値") {
            testApplication {
                application { shouldThrow<IllegalArgumentException> { installRequestDeadline(0.milliseconds) } }
                client.get("/")
            }
        }
    })
