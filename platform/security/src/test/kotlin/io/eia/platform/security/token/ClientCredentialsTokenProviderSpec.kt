package io.eia.platform.security.token

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.eia.platform.security.jwt.MutableClock
import io.eia.platform.security.jwt.NOW
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretNotFound
import io.eia.platform.security.secret.SecretUnreadable
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.sdk.http.token.AccessToken
import io.eia.shared.sdk.http.token.ClientSecretUnavailable
import io.eia.shared.sdk.http.token.TokenError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private fun Result<AccessToken, TokenError>.error(): TokenError = shouldBeInstanceOf<Result.Err<TokenError>>().error

/** [block] の間、[ClientCredentialsTokenProvider] のロガーに出たログを集める。 */
private suspend fun capturing(block: suspend () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(ClientCredentialsTokenProvider::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val level = logger.level
    logger.level = Level.DEBUG
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        logger.level = level
    }
    return appender.list
}

/** JVM の部品(SecretProvider・ログ)をつないだところだけを検査する。取得・キャッシュ・分類は SDK の ClientCredentialsTokenProviderSpec。 */
class ClientCredentialsTokenProviderSpec :
    FunSpec({
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

        test("Secret を取得できなければ要求せずに失敗し、cause は SecretProvider のエラー") {
            val endpoint = FakeTokenEndpoint(FakeTokenEndpoint.ok())

            provider(endpoint, secrets = EnvSecretProvider(emptyMap())).token().error() shouldBe
                ClientSecretUnavailable.Permanent(SecretNotFound(SECRET_NAME))
            endpoint.requests.size shouldBe 0
        }

        test("Secret の一時的な失敗は Retryable") {
            provider(FakeTokenEndpoint(FakeTokenEndpoint.ok()), secrets = { err(SecretUnreadable(SECRET_NAME, "io")) })
                .token()
                .error()
                .shouldBeInstanceOf<ClientSecretUnavailable.Temporary>()
                .shouldBeInstanceOf<DomainError.Retryable>()
        }

        test("期限内のトークンを使い続けるとき・取得できないときは、このクラスのロガーに WARN を残す") {
            val clock = MutableClock()
            val logs =
                capturing {
                    val endpoint =
                        FakeTokenEndpoint(FakeTokenEndpoint.ok(), FakeTokenEndpoint.status(HttpStatusCode.ServiceUnavailable))
                    val provider = provider(endpoint, clock)
                    provider.token()
                    clock.now = NOW + 280.seconds
                    provider.token()
                    provider(FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.Unauthorized))).token()
                }

            logs.filter { it.level == Level.WARN }.map { it.formattedMessage.substringBefore(" code=") } shouldBe
                listOf("期限前のトークンの取り直しに失敗したため、期限内のトークンを使い続けます", "トークンを取得できません")
        }

        test("設定の検証は SDK の設定で行う(スコープの形式・期間)") {
            shouldThrow<IllegalArgumentException> {
                ClientCredentialsConfig(URI.create("http://idp.test/token"), CLIENT_ID, SECRET_NAME, scopes = setOf("a b"))
            }
            shouldThrow<IllegalArgumentException> { ClientCredentialsConfig(URI.create("ftp://idp.test/token"), CLIENT_ID, SECRET_NAME) }
        }
    })
