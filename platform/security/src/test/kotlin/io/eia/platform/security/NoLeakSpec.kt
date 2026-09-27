package io.eia.platform.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import io.eia.platform.observability.logging.EiaLogEncoder
import io.eia.platform.security.jwt.MutableClock
import io.eia.platform.security.jwt.NOW
import io.eia.platform.security.jwt.TestKeys
import io.eia.platform.security.jwt.noneWithSignature
import io.eia.platform.security.jwt.signHs256WithPublicKey
import io.eia.platform.security.jwt.signRsa
import io.eia.platform.security.jwt.tamperSignature
import io.eia.platform.security.jwt.unsigned
import io.eia.platform.security.jwt.validClaims
import io.eia.platform.security.jwt.verifier
import io.eia.platform.security.ktor.eiaJwt
import io.eia.platform.security.ktor.requireScopes
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.token.CLIENT_SECRET
import io.eia.platform.security.token.FakeTokenEndpoint
import io.eia.platform.security.token.provider
import io.eia.platform.security.token.tokenJson
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val ACCESS_TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJsZWFrLXRlc3QifQ.bGVhay1zaWduYXR1cmUtdmFsdWU"

/**
 * 鍵・トークン・Secret が、ログ(ライブラリのものを含む)・エラーのメッセージ・toString に出ないことを確かめる(Framework 12・14)。
 *
 * - 主な対策は「そもそも出さない」こと。このモジュールのログは、生のメッセージ(マスキングの前)にも値がないことを確かめる。
 * - ライブラリのログ(Ktor の HttpCallValidator は TRACE で例外をメッセージごと記録する)は中身を制御できないため、
 *   最後の防御である ② のマスキング(`EiaLogEncoder`)を通した出力に値がないことを確かめる(ADR-0019 §7)。
 */
private class CapturedLogs {
    val events = mutableListOf<ILoggingEvent>()
    private val encoder =
        EiaLogEncoder().apply {
            context = LoggerFactory.getILoggerFactory() as LoggerContext
            service = "security-test"
            start()
        }

    /**
     * このモジュールのログは生のメッセージ(引数を埋めた後)・例外・MDC と EiaLogEncoder の JSON の出力、
     * ライブラリのログは EiaLogEncoder の JSON の出力。
     */
    fun outputs(): List<String> =
        events.flatMap { event ->
            val raw =
                if (event.loggerName.startsWith(OWN_LOGGERS)) {
                    listOfNotNull(
                        event.formattedMessage,
                        event.throwableProxy?.let { "${it.className}: ${it.message}" },
                        event.mdcPropertyMap?.toString(),
                    )
                } else {
                    emptyList()
                }
            raw + encoder.encode(event).decodeToString()
        }

    companion object {
        const val OWN_LOGGERS = "io.eia.platform.security"
    }
}

private suspend fun capturing(block: suspend () -> Unit): CapturedLogs {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender =
        ListAppender<ILoggingEvent>().apply {
            this.context = context
            start()
        }
    val previous = root.level
    root.addAppender(appender)
    root.level = Level.TRACE
    try {
        block()
    } finally {
        root.level = previous
        root.detachAppender(appender)
    }
    return CapturedLogs().apply { events += appender.list }
}

private fun String.shouldNotLeak(secrets: Collection<String>) {
    secrets.forEach { secret -> this shouldNotContain secret }
}

/** トークンの全体と、署名部・ペイロード部(それだけでも漏れれば再構成の手がかりになる)。 */
private fun parts(token: String): List<String> = listOf(token) + token.split('.').filter { it.length >= 16 }

class NoLeakSpec :
    FunSpec({
        test("拒否したトークンは、どの理由でもログ・エラーのメッセージ・応答に出ない") {
            val tokens =
                listOf(
                    signRsa(validClaims().audience("inventory-api").build()),
                    signRsa(validClaims(now = NOW - 10.minutes).build()),
                    unsigned(validClaims().build()),
                    noneWithSignature(validClaims().build()),
                    signHs256WithPublicKey(validClaims().build()),
                    tamperSignature(signRsa(validClaims().build())),
                    signRsa(validClaims().build(), key = TestKeys.rsaUnknown),
                )
            val verifier = verifier()
            val errors = mutableListOf<String>()
            val bodies = mutableListOf<String>()

            val logs =
                capturing {
                    tokens.forEach { token ->
                        val result = verifier.verify(token)
                        errors += result.toString()
                        (result as? Result.Err)?.error?.let { errors += it.message }
                    }
                    testApplication {
                        install(Authentication) { eiaJwt { this.verifier = verifier } }
                        routing { authenticate { requireScopes("sales.order:write") { post("/v1/orders") { call.respondText("ok") } } } }
                        tokens.forEach { token ->
                            val response = client.post("/v1/orders") { header(HttpHeaders.Authorization, "Bearer $token") }
                            bodies += response.headers.entries().toString()
                            bodies += response.bodyAsText()
                        }
                    }
                }

            logs.events.filter { it.loggerName.startsWith("io.eia.platform.security") }.shouldNotBeEmpty()
            val secrets = tokens.flatMap(::parts)
            (logs.outputs() + errors + bodies).forEach { it.shouldNotLeak(secrets) }
        }

        test("JWKS を取得できないときの WARN にも、トークンは出ない") {
            val token = signRsa(validClaims().build())
            val failing = JWKSource<SecurityContext> { _, _ -> throw KeySourceException("jwks down for $token") }

            val logs = capturing { verifier(source = failing).verify(token) }

            logs.events.filter { it.level == Level.WARN }.shouldNotBeEmpty()
            logs.outputs().forEach { it.shouldNotLeak(parts(token)) }
        }

        test("トークンの取得: Client Secret・取得したトークン・応答の本文は、成功でも失敗でもログとエラーに出ない") {
            val errorBody = """{"error":"invalid_client","error_description":"secret $CLIENT_SECRET is wrong"}"""
            val errors = mutableListOf<String>()
            val clock = MutableClock()

            val logs =
                capturing {
                    // 成功 → 期限前の取り直しの失敗(期限内のトークンを使い続ける WARN)
                    val endpoint =
                        FakeTokenEndpoint(
                            FakeTokenEndpoint.ok(tokenJson(ACCESS_TOKEN)),
                            FakeTokenEndpoint.status(HttpStatusCode.Unauthorized, errorBody),
                        )
                    val provider = provider(endpoint, clock)
                    errors += provider.token().toString()
                    clock.now = NOW + 280.seconds
                    errors += provider.token().toString()

                    // 失敗の各種
                    listOf(
                        FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.Unauthorized, errorBody)),
                        FakeTokenEndpoint(FakeTokenEndpoint.status(HttpStatusCode.InternalServerError, errorBody)),
                        FakeTokenEndpoint(FakeTokenEndpoint.ok(tokenJson(ACCESS_TOKEN, type = "mac"))),
                        FakeTokenEndpoint({ throw IOException("failed with Authorization: Basic $CLIENT_SECRET") }),
                    ).forEach { failing ->
                        val result = provider(failing).token()
                        errors += result.toString()
                        (result as? Result.Err)?.error?.let { errors += it.message }
                    }
                }

            logs.events.filter { it.level == Level.WARN }.shouldNotBeEmpty()
            val secrets = listOf(CLIENT_SECRET, ACCESS_TOKEN) + parts(ACCESS_TOKEN)
            (logs.outputs() + errors).forEach { it.shouldNotLeak(secrets) }
        }

        test("SecretProvider: PEM の秘密鍵を読むときも、エラーと toString に値は出ない") {
            val pem = TestKeys.privateKeyPem()
            val pemLines = pem.lines().filter { it.length >= 32 }
            val dir = tempdir()
            val file = File(dir, "tls.key").apply { writeText(pem) }
            val errors = mutableListOf<String>()

            val logs =
                capturing {
                    val ok = EnvSecretProvider(mapOf("TLS_KEY_FILE" to file.absolutePath)).get(SecretName("TLS_KEY"))
                    errors += ok.toString()
                    errors += (ok as Result.Ok).value.toString()
                    val both = EnvSecretProvider(mapOf("TLS_KEY" to pem, "TLS_KEY_FILE" to file.absolutePath)).get(SecretName("TLS_KEY"))
                    errors += both.toString()
                    errors += (both as Result.Err).error.message
                }

            (logs.outputs() + errors).forEach { it.shouldNotLeak(pemLines) }
        }

        test("最後の防御: 誤って鍵やトークンをログに書いても、② のマスキング(PEM の伏せ字を含む)で出力には残らない") {
            val pem = TestKeys.privateKeyPem()
            val secret = Secret(CLIENT_SECRET)
            val logger = LoggerFactory.getLogger("io.eia.sample.Careless")

            val logs =
                capturing {
                    logger.error("鍵を読み込みました key={}", pem)
                    logger.error("Authorization: Bearer {}", ACCESS_TOKEN)
                    logger.error("client_secret={} loaded={}", CLIENT_SECRET, secret)
                    logger.error("起動に失敗しました", IllegalStateException("bad key\n$pem"))
                }

            // io.eia.sample はこのモジュールのロガーではないため、outputs() は EiaLogEncoder の出力だけになる
            val encoded = logs.outputs()
            encoded.size shouldBe logs.events.size
            encoded.forEach { line ->
                line.shouldNotLeak(pem.lines().filter { it.length >= 32 } + ACCESS_TOKEN + parts(ACCESS_TOKEN) + CLIENT_SECRET)
            }
            encoded.joinToString("\n") shouldContain "Secret(***)"
        }
    })
