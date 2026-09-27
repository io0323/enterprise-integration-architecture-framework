package io.eia.platform.security.jwt

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 差し替えられる JWKS を返す IdP の代わり。取得の回数を数え、止めることもできる。 */
private class FakeIdp(
    @Volatile var jwks: JWKSet,
) : ResourceRetriever {
    val fetches = AtomicInteger()

    @Volatile var down = false

    override fun retrieveResource(url: URL): Resource {
        fetches.incrementAndGet()
        if (down) throw IOException("connection refused")
        return Resource(jwks.toString(true), "application/json")
    }
}

/** 実時間で動く Nimbus のキャッシュを、短い時間の設定で確かめる。 */
private fun jwksConfig(
    cacheTtl: Duration = 5.minutes,
    rateLimit: Duration = 30.seconds,
    outage: Duration = 15.minutes,
    refreshAhead: Duration = 30.seconds,
    refreshTimeout: Duration = 15.seconds,
) = JwksConfig(
    cacheTtl = cacheTtl,
    refreshAhead = refreshAhead,
    refreshTimeout = refreshTimeout,
    rateLimitMinInterval = rateLimit,
    outageTolerance = outage,
)

private fun rotatingVerifier(
    idp: FakeIdp,
    jwks: JwksConfig,
): JwtVerifier =
    JwtVerifier(
        JwtVerifierConfig(ISSUER, AUDIENCE, JWKS_URI, jwks = jwks),
        idp,
        // exp の判定は実時間で行う(キャッシュが実時間で動くため、トークンも実時間で作る)
        Clock.System,
        null,
    )

private fun token(key: com.nimbusds.jose.jwk.RSAKey): String = signRsa(validClaims(now = Clock.System.now()).build(), key = key)

class JwksRotationSpec :
    FunSpec({
        test("JWKS をキャッシュし、検証のたびには取得しない") {
            val idp = FakeIdp(TestKeys.jwks(TestKeys.rsa))
            rotatingVerifier(idp, jwksConfig()).use { verifier ->
                repeat(5) { verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>() }
                idp.fetches.get() shouldBe 1
            }
        }

        test("鍵のローテーション: 未知の kid のトークンが来たら JWKS を取り直し、新しい鍵で検証する") {
            val idp = FakeIdp(TestKeys.jwks(TestKeys.rsa))
            rotatingVerifier(idp, jwksConfig(rateLimit = 50.milliseconds)).use { verifier ->
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()

                // IdP が鍵を差し替えた(新旧の鍵を並べて公開する期間)
                idp.jwks = TestKeys.jwks(TestKeys.rsaRotated, TestKeys.rsa)
                Thread.sleep(100)

                verifier.verifyBlocking(token(TestKeys.rsaRotated)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                idp.fetches.get() shouldBe 2
            }
        }

        test("未知の kid のトークンを大量に送られても、取り直しは最小の間隔に 1 回まで。その間は 401(503 にしない)") {
            val idp = FakeIdp(TestKeys.jwks(TestKeys.rsa))
            rotatingVerifier(idp, jwksConfig(rateLimit = 30.seconds)).use { verifier ->
                verifier.verifyBlocking(token(TestKeys.rsa))

                val results = (1..50).map { verifier.verifyBlocking(token(TestKeys.rsaUnknown)) }

                results.forEach { it shouldBeEqual Result.Err(JwtVerificationError.InvalidToken(JwtRejectionReason.UNKNOWN_KEY)) }
                // 最初の取得 + 未知の kid による取り直し(最小の間隔の中では 1 回だけ)
                (idp.fetches.get() <= 2) shouldBe true
            }
        }

        test("IdP が止まっても、最後に取得した JWKS で outageTolerance の間は検証を続け、過ぎたら 503(KeysUnavailable)") {
            val idp = FakeIdp(TestKeys.jwks(TestKeys.rsa))
            val config =
                jwksConfig(
                    cacheTtl = 400.milliseconds,
                    refreshAhead = 50.milliseconds,
                    refreshTimeout = 100.milliseconds,
                    rateLimit = 10.milliseconds,
                    outage = 1200.milliseconds,
                )
            rotatingVerifier(idp, config).use { verifier ->
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                idp.down = true

                // キャッシュ(400ms)は切れたが、outageTolerance(1200ms)の中
                Thread.sleep(600)
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()

                // outageTolerance も過ぎた
                Thread.sleep(900)
                verifier.verifyBlocking(token(TestKeys.rsa)) shouldBeEqual Result.Err(JwtVerificationError.KeysUnavailable)

                // IdP が戻れば、また検証できる
                idp.down = false
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
            }
        }

        test("IdP が止まって使える JWKS がないとき、取り直しの頻度の制限の中でも、すべて 503(KeysUnavailable)にする") {
            // 起動直後から IdP が止まっている
            val idp = FakeIdp(TestKeys.jwks(TestKeys.rsa)).apply { down = true }
            rotatingVerifier(idp, jwksConfig(rateLimit = 30.seconds)).use { verifier ->
                repeat(5) {
                    verifier.verifyBlocking(token(TestKeys.rsa)) shouldBeEqual Result.Err(JwtVerificationError.KeysUnavailable)
                }
            }
        }

        test("outageTolerance を過ぎた後も、取り直しの頻度の制限の中のリクエストは 503 のまま。IdP が戻れば検証できる") {
            val idp = FakeIdp(TestKeys.jwks(TestKeys.rsa))
            val config =
                jwksConfig(
                    cacheTtl = 400.milliseconds,
                    refreshAhead = 50.milliseconds,
                    refreshTimeout = 100.milliseconds,
                    rateLimit = 300.milliseconds,
                    outage = 600.milliseconds,
                )
            rotatingVerifier(idp, config).use { verifier ->
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                idp.down = true
                Thread.sleep(800)

                repeat(5) {
                    verifier.verifyBlocking(token(TestKeys.rsa)) shouldBeEqual Result.Err(JwtVerificationError.KeysUnavailable)
                }

                idp.down = false
                Thread.sleep(350)
                verifier.verifyBlocking(token(TestKeys.rsa)).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                // IdP が戻った後は、未知の kid は頻度の制限の中でも 401 に戻る
                repeat(3) {
                    verifier.verifyBlocking(token(TestKeys.rsaUnknown)) shouldBeEqual
                        Result.Err(JwtVerificationError.InvalidToken(JwtRejectionReason.UNKNOWN_KEY))
                }
            }
        }
    })
