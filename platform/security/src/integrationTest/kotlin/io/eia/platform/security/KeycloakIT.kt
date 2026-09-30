package io.eia.platform.security

import io.eia.platform.security.jwt.JwtRejectionReason
import io.eia.platform.security.jwt.JwtVerificationError
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.JwtVerifierConfig
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.ktor.eiaJwt
import io.eia.platform.security.ktor.requireScopes
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.token.AccessToken
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.platform.security.token.TokenRequestRejected
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI

private const val READ = "sales.order:read"
private const val WRITE = "sales.order:write"
private const val UNAUTHORIZED = 401
private val clientSecret: String = newClientSecret()

class KeycloakIT :
    FunSpec({
        val keycloak = keycloakContainer(clientSecret)

        lateinit var backchannel: String
        val http = HttpClient(CIO)

        beforeSpec {
            keycloak.start()
            backchannel = "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_HTTP_PORT)}/realms/eiaf"
        }
        afterSpec {
            http.close()
            keycloak.stop()
        }

        fun verifier(audience: String = "order-api"): JwtVerifier =
            JwtVerifier(JwtVerifierConfig(ISSUER, audience, URI.create("$backchannel/protocol/openid-connect/certs")))

        fun tokenProvider(
            scopes: Set<String>,
            secret: String = clientSecret,
            client: HttpClient = http,
        ): ClientCredentialsTokenProvider =
            ClientCredentialsTokenProvider(
                ClientCredentialsConfig(URI.create("$backchannel/protocol/openid-connect/token"), CLIENT_ID, SECRET_NAME, scopes),
                client,
                EnvSecretProvider(mapOf(SECRET_NAME.value to secret)),
            )

        suspend fun token(scopes: Set<String> = setOf(READ, WRITE)): AccessToken =
            tokenProvider(scopes).token().shouldBeInstanceOf<Result.Ok<AccessToken>>().value

        test("Client Credentials で取得したトークンが検証を通り、要求したスコープと aud・iss を持つ") {
            val token = token()

            verifier().use { verifier ->
                val verified = verifier.verify(token.reveal()).shouldBeInstanceOf<Result.Ok<VerifiedToken>>().value
                verified.issuer shouldBe ISSUER
                verified.audience shouldBe listOf("order-api")
                verified.scopes shouldBe setOf(READ, WRITE)
                verified.clientId shouldBe CLIENT_ID
            }
            token.scopes shouldBe setOf(READ, WRITE)
        }

        test("スコープを要求しなければ、トークンに sales.order のスコープは入らない(realm では optional のスコープ)") {
            val token = token(scopes = emptySet())

            verifier().use { verifier ->
                verifier
                    .verify(token.reveal())
                    .shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                    .value.scopes shouldBe emptySet()
            }
        }

        test("aud の違う検証器では、同じトークンが 401(bad_audience)になる") {
            val token = token()

            verifier(audience = "inventory-api").use { verifier ->
                verifier.verify(token.reveal()) shouldBeEqual
                    Result.Err(JwtVerificationError.InvalidToken(JwtRejectionReason.BAD_AUDIENCE))
            }
            verifier(audience = "inventory-api").use { verifier ->
                testApplication {
                    install(Authentication) { eiaJwt { this.verifier = verifier } }
                    routing { authenticate { requireScopes(WRITE) { post("/v1/orders") { call.respondText("created") } } } }

                    val response = client.post("/v1/orders") { header(HttpHeaders.Authorization, token.authorizationHeader()) }
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer error="invalid_token""""
                }
            }
        }

        test("Ktor: write のスコープを持つトークンは通り、read だけのトークンは 403") {
            val readWrite = token()
            val readOnly = token(scopes = setOf(READ))

            verifier().use { verifier ->
                testApplication {
                    install(Authentication) { eiaJwt { this.verifier = verifier } }
                    routing { authenticate { requireScopes(WRITE) { post("/v1/orders") { call.respondText("created") } } } }

                    client
                        .post("/v1/orders") { header(HttpHeaders.Authorization, readWrite.authorizationHeader()) }
                        .status shouldBe HttpStatusCode.OK
                    val forbidden = client.post("/v1/orders") { header(HttpHeaders.Authorization, readOnly.authorizationHeader()) }
                    forbidden.status shouldBe HttpStatusCode.Forbidden
                    forbidden.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer error="insufficient_scope", scope="$WRITE""""
                }
            }
        }

        test("Client Secret が違えば 401 で NonRetryable(Keycloak 26.7 のエラーコードは unauthorized_client)。既定の Resilience でもリトライしない") {
            CountingHttpClient().use { counting ->
                val provider = tokenProvider(setOf(READ), secret = "wrong-secret", client = counting.client)
                val error = provider.token().shouldBeInstanceOf<Result.Err<*>>().error
                error shouldBe TokenRequestRejected(UNAUTHORIZED, "unauthorized_client")
                error.shouldBeInstanceOf<DomainError.NonRetryable>()
                counting.sent shouldBe 1
            }
        }
    })
