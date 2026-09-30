package io.eia.platform.security.ktor

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.NOW
import io.eia.platform.security.jwt.TestKeys
import io.eia.platform.security.jwt.config
import io.eia.platform.security.jwt.noneWithSignature
import io.eia.platform.security.jwt.signHs256WithPublicKey
import io.eia.platform.security.jwt.signRsa
import io.eia.platform.security.jwt.tamperPayload
import io.eia.platform.security.jwt.tamperSignature
import io.eia.platform.security.jwt.unsigned
import io.eia.platform.security.jwt.validClaims
import io.eia.platform.security.jwt.verifier
import io.eia.shared.kernel.Result
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Date
import kotlin.time.Duration.Companion.minutes

private const val WRITE = "sales.order:write"
private const val READ = "sales.order:read"

private fun ApplicationTestBuilder.orderApi(
    verifier: JwtVerifier = verifier(),
    observability: Boolean = false,
) {
    if (observability) {
        install(ServerObservability) {
            val config = ObservabilityConfig.of("security-test").shouldBeInstanceOf<Result.Ok<ObservabilityConfig>>().value
            runtime = Observability.init(config, TelemetrySinks(), installLogAppender = false)
        }
    }
    install(Authentication) {
        eiaJwt {
            this.verifier = verifier
            realm = "eiaf"
        }
    }
    routing {
        authenticate {
            requireScopes(WRITE) { post("/v1/orders") { call.respondText("created") } }
            requireScopes(READ) { get("/v1/orders/1") { call.respondText("order ${call.verifiedToken()?.clientId}") } }
            requireScopes(READ, WRITE) { post("/v1/orders/1/cancel") { call.respondText("cancelled") } }
        }
        authenticate(optional = true) {
            requireScopes(READ) { get("/optional") { call.respondText("optional") } }
        }
    }
}

private suspend fun ApplicationTestBuilder.post(token: String?): HttpResponse =
    client.post("/v1/orders") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

class KtorAuthSpec :
    FunSpec({
        val readOnly = signRsa(validClaims(scope = READ).build())
        val readWrite = signRsa(validClaims().build())

        test("スコープを満たすトークンは処理を通し、principal から呼び出し元を取り出せる") {
            testApplication {
                orderApi()
                post(readWrite).status shouldBe HttpStatusCode.OK
                client.get("/v1/orders/1") { header(HttpHeaders.Authorization, "Bearer $readOnly") }.bodyAsText() shouldBe "order eiaf-e2e"
            }
        }

        test("トークンがなければ 401 と WWW-Authenticate: Bearer(error を付けない。RFC 6750 §3.1)") {
            testApplication {
                orderApi()
                val response = post(null)

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer realm="eiaf""""
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers[HttpHeaders.ContentType] shouldBe "application/problem+json"
                response.bodyAsText() shouldBe
                    """{"type":"https://eiaf.example/problems/unauthorized","title":"Unauthorized","status":401}"""
            }
        }

        test("Bearer 以外の方式(Basic)はトークンなしと同じ扱い") {
            testApplication {
                orderApi()
                val response = client.post("/v1/orders") { header(HttpHeaders.Authorization, "Basic ZWlhZjpzZWNyZXQ=") }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer realm="eiaf""""
            }
        }

        test("不正なトークンはすべて 401 と error=\"invalid_token\"。拒否した理由は応答に出さない") {
            val cases =
                mapOf(
                    "iss の不一致" to signRsa(validClaims().issuer("http://evil.test/realms/eiaf").build()),
                    "aud の不一致" to signRsa(validClaims().audience("inventory-api").build()),
                    "期限切れ" to signRsa(validClaims(now = NOW - 10.minutes).build()),
                    "exp なし" to signRsa(validClaims().expirationTime(null).build()),
                    "iat なし" to signRsa(validClaims().issueTime(null).build()),
                    "未来の nbf" to signRsa(validClaims().notBeforeTime(Date((NOW + 10.minutes).toEpochMilliseconds())).build()),
                    "alg=none" to unsigned(validClaims().build()),
                    "alg=none + 署名" to noneWithSignature(validClaims().build()),
                    "HS256(公開鍵を HMAC の鍵に)" to signHs256WithPublicKey(validClaims().build()),
                    "RS384(設定にない)" to signRsa(validClaims().build(), algorithm = JWSAlgorithm.RS384),
                    "署名の改竄" to tamperSignature(readWrite),
                    "ペイロードの改竄" to tamperPayload(readOnly, validClaims().build()),
                    "未知の鍵" to signRsa(validClaims().build(), key = TestKeys.rsaUnknown),
                    "形式の不正" to "not-a-jwt",
                )
            testApplication {
                orderApi()
                cases.forEach { (name, token) ->
                    withClue(name) {
                        val response = post(token)
                        response.status shouldBe HttpStatusCode.Unauthorized
                        response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer realm="eiaf", error="invalid_token""""
                        response.bodyAsText() shouldBe
                            """{"type":"https://eiaf.example/problems/unauthorized","title":"Unauthorized","status":401}"""
                    }
                }
            }
        }

        test("Bearer の形式が不正・Authorization が複数なら 401 invalid_token") {
            testApplication {
                orderApi()
                listOf("Bearer", "Bearer ", "Bearer a b", "bearer\t$readWrite").forEach { value ->
                    withClue(value) {
                        val response = client.post("/v1/orders") { header(HttpHeaders.Authorization, value) }
                        response.status shouldBe HttpStatusCode.Unauthorized
                        response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer realm="eiaf", error="invalid_token""""
                    }
                }
                val twice =
                    client.post("/v1/orders") {
                        header(HttpHeaders.Authorization, "Bearer $readWrite")
                        header(HttpHeaders.Authorization, "Bearer $readWrite")
                    }
                twice.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("方式の名前は大文字小文字を区別しない(RFC 9110 §11.1)") {
            testApplication {
                orderApi()
                client.post("/v1/orders") { header(HttpHeaders.Authorization, "bEaReR $readWrite") }.status shouldBe HttpStatusCode.OK
            }
        }

        test("スコープが足りなければ 403 と error=\"insufficient_scope\" と必要なスコープ") {
            testApplication {
                orderApi()
                val response = post(readOnly)

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe
                    """Bearer realm="eiaf", error="insufficient_scope", scope="sales.order:write""""
                response.bodyAsText() shouldBe """{"type":"https://eiaf.example/problems/forbidden","title":"Forbidden","status":403}"""
            }
        }

        test("複数のスコープはすべて必要。WWW-Authenticate には全部を並べる") {
            testApplication {
                orderApi()
                val response = client.post("/v1/orders/1/cancel") { header(HttpHeaders.Authorization, "Bearer $readOnly") }

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe
                    """Bearer realm="eiaf", error="insufficient_scope", scope="sales.order:read sales.order:write""""
                client
                    .post("/v1/orders/1/cancel") { header(HttpHeaders.Authorization, "Bearer $readWrite") }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("authenticate(optional = true) の中でも、トークンがなければ requireScopes は通さない(401)") {
            testApplication {
                orderApi()
                val response = client.get("/optional")
                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer"
            }
        }

        test("JWKS を取得できず検証できなければ 503(トークンの問題ではないため 401 にしない)") {
            val failing = JWKSource<SecurityContext> { _, _ -> throw KeySourceException("down") }
            testApplication {
                orderApi(verifier(source = failing))
                val response = post(readWrite)

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe null
                response.bodyAsText() shouldContain "\"type\":\"https://eiaf.example/problems/service-unavailable\""
            }
        }

        test("401 / 403 の Problem Details には、その処理の correlationId を入れる(ADR-0022 §2)") {
            testApplication {
                orderApi(observability = true)
                listOf(post(null), post(readOnly)).forEach { response ->
                    val correlationId = response.headers[CorrelationHeaders.X_CORRELATION_ID]
                    response.bodyAsText() shouldContain "\"correlationId\":\"$correlationId\""
                }
            }
        }

        test("aud の違う検証器では、同じトークンが 401 になる") {
            testApplication {
                orderApi(verifier(config = config(audience = "inventory-api")))
                post(readWrite).status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
