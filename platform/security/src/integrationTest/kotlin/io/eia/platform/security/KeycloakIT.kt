package io.eia.platform.security

import io.eia.platform.security.jwt.JwtRejectionReason
import io.eia.platform.security.jwt.JwtVerificationError
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.JwtVerifierConfig
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.ktor.eiaJwt
import io.eia.platform.security.ktor.requireScopes
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.token.AccessToken
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.platform.security.token.TokenRequestRejected
import io.eia.platform.testsupport.InfraImages
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat

private const val HTTP_PORT = 8080
private const val MANAGEMENT_PORT = 9000
private const val REALM_IMPORT = "/opt/keycloak/data/import/realm-eiaf.json"
private const val UNAUTHORIZED = 401
private const val SECRET_BYTES = 16
private const val STARTUP_TIMEOUT_MINUTES = 3L
private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(STARTUP_TIMEOUT_MINUTES)

/**
 * compose の `KC_HOSTNAME` は `http://localhost:19180` に固定している(ADR-0016 §7)。
 * テストではホスト側のポートが毎回変わるため、`KC_HOSTNAME` を上書きして iss を固定し、
 * トークンと JWKS はバックチャネル(`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`)として割り当てられたポートから取得する。
 * iss(frontend の URL)と JWKS の取得先(バックチャネルの URL)を別々に設定できることの確認にもなる(ADR-0019 §3)。
 */
private const val FRONTEND_URL = "http://keycloak.it:8080"
private const val ISSUER = "$FRONTEND_URL/realms/eiaf"
private const val CLIENT_ID = "eiaf-e2e"
private const val READ = "sales.order:read"
private const val WRITE = "sales.order:write"
private val SECRET_NAME = SecretName("EIAF_E2E_CLIENT_SECRET")

/** `infra/local/keycloak/realm-eiaf.json`(images.env と同じ infra/local から探す)。 */
private fun realmFile(): Path =
    Path
        .of(System.getProperty(InfraImages.FILE_PROPERTY))
        .toAbsolutePath()
        .parent
        .resolve("keycloak/realm-eiaf.json")

/**
 * Client Secret はテストごとに乱数で作る。RFC 6749 §2.3.1 の form-urlencoded を実際の IdP で確かめるため、
 * エンコードが必要な記号(`:` `+` `/` `%` 空白 `~` `*`)を含める。JSON の realm に埋め込まれるため `"` と `\` は使わない。
 */
private val clientSecret: String = randomHex() + ":+/% ~*"

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

class KeycloakIT :
    FunSpec({
        val keycloak =
            GenericContainer(InfraImages.get("KEYCLOAK_IMAGE"))
                .withCommand("start-dev", "--import-realm")
                .withEnv("KC_HOSTNAME", FRONTEND_URL)
                .withEnv("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true")
                .withEnv("KC_HEALTH_ENABLED", "true")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", randomHex())
                .withEnv("EIAF_E2E_CLIENT_SECRET", clientSecret)
                .withCopyFileToContainer(MountableFile.forHostPath(realmFile()), REALM_IMPORT)
                .withExposedPorts(HTTP_PORT, MANAGEMENT_PORT)
                .waitingFor(Wait.forHttp("/health/ready").forPort(MANAGEMENT_PORT).withStartupTimeout(STARTUP_TIMEOUT))

        lateinit var backchannel: String
        val http = HttpClient(CIO)

        beforeSpec {
            keycloak.start()
            backchannel = "http://${keycloak.host}:${keycloak.getMappedPort(HTTP_PORT)}/realms/eiaf"
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
        ): ClientCredentialsTokenProvider =
            ClientCredentialsTokenProvider(
                ClientCredentialsConfig(URI.create("$backchannel/protocol/openid-connect/token"), CLIENT_ID, SECRET_NAME, scopes),
                http,
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

        test("Client Secret が違えば 401 で NonRetryable(Keycloak 26.7 のエラーコードは unauthorized_client)") {
            val error = tokenProvider(setOf(READ), secret = "wrong-secret").token().shouldBeInstanceOf<Result.Err<*>>().error
            error shouldBe TokenRequestRejected(UNAUTHORIZED, "unauthorized_client")
            error.shouldBeInstanceOf<DomainError.NonRetryable>()
        }
    })
