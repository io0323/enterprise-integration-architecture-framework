package io.eia.tools.schemapublish

import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.seconds

private val FULL =
    mapOf(
        RegistryAuth.TOKEN_URL to "http://keycloak.test/realms/eiaf/protocol/openid-connect/token",
        RegistryAuth.CLIENT_ID to "eiaf-schema-publisher",
        RegistryAuth.CLIENT_SECRET.value to "s3",
    )

class RegistryAuthSpec :
    FunSpec({
        test("どれもなければ認証しない(普段の起動)") {
            RegistryAuth.setting(emptyMap()) shouldBe RegistryAuth.Setting.None
        }

        test("3 つがそろえば、トークンを付ける設定になる") {
            val setting = RegistryAuth.setting(FULL).shouldBeInstanceOf<RegistryAuth.Setting.Configured>()
            setting.config.clientId shouldBe "eiaf-schema-publisher"
        }

        test("Client Secret はファイルでもよい") {
            RegistryAuth
                .setting(FULL - RegistryAuth.CLIENT_SECRET.value + (RegistryAuth.CLIENT_SECRET.value + "_FILE" to "/run/secrets/x"))
                .shouldBeInstanceOf<RegistryAuth.Setting.Configured>()
        }

        test("一部だけなら設定の誤り(黙って認証なしで送らない)") {
            for (missing in FULL.keys) {
                RegistryAuth.setting(FULL - missing).shouldBeInstanceOf<RegistryAuth.Setting.Invalid>()
            }
        }

        test("トークンエンドポイントが URL でなければ設定の誤り") {
            RegistryAuth.setting(FULL + (RegistryAuth.TOKEN_URL to "keycloak")).shouldBeInstanceOf<RegistryAuth.Setting.Invalid>()
        }

        test("要求ごとに Authorization を付ける。取れなければ付けない") {
            val seen = mutableListOf<String?>()
            var token: String? = "Bearer t1"
            val http =
                HttpClient(MockEngine { request -> respondOk().also { seen += request.headers[HttpHeaders.Authorization] } }) {
                    install(bearerToken { token })
                }
            http.get("http://registry.test/a")
            token = null
            http.get("http://registry.test/b")
            seen shouldBe listOf("Bearer t1", null)
        }
    })

class FirstTokenSpec :
    FunSpec({
        test("起動の直後に取れなくても、再試行して取る") {
            var calls = 0
            val http =
                HttpClient(
                    MockEngine {
                        calls++
                        if (calls == 1) {
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond(
                                """{"access_token":"t","token_type":"Bearer","expires_in":300}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                )
            val config = (RegistryAuth.setting(FULL) as RegistryAuth.Setting.Configured).config
            // 部品の中の再試行を止め、外側(firstToken)の再試行だけを確かめる
            val tokens =
                ClientCredentialsTokenProvider(
                    config,
                    http,
                    EnvSecretProvider(FULL),
                    resilience = Resilience("token", ResilienceConfig(attemptTimeout = 5.seconds, retry = null, circuitBreaker = null)),
                )
            val waits = mutableListOf<String>()
            val result = firstToken(tokens, { waits += it }, sleep = {})
            result.shouldBeInstanceOf<Result.Ok<*>>()
            waits.size shouldBe 1
        }
    })
