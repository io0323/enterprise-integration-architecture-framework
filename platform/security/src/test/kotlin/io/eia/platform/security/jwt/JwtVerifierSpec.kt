package io.eia.platform.security.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal fun config(
    audience: String = AUDIENCE,
    algorithms: Set<String> = SignatureAlgorithms.DEFAULT,
    maxTokenLifetime: kotlin.time.Duration = JwtVerifierConfig.DEFAULT_MAX_TOKEN_LIFETIME,
): JwtVerifierConfig =
    JwtVerifierConfig(
        issuer = ISSUER,
        audience = audience,
        jwksUri = JWKS_URI,
        algorithms = algorithms,
        maxTokenLifetime = maxTokenLifetime,
    )

internal fun verifier(
    jwks: JWKSet = TestKeys.jwks(TestKeys.rsa, TestKeys.ec),
    config: JwtVerifierConfig = config(),
    clock: Clock = MutableClock(),
    source: JWKSource<SecurityContext> = ImmutableJWKSet(jwks),
    reader: InMemoryMetricReader? = null,
): JwtVerifier {
    val meter =
        reader?.let {
            SdkMeterProvider
                .builder()
                .registerMetricReader(it)
                .build()
                .get("test")
        }
    return JwtVerifier(config, source, clock, meter)
}

private fun JwtVerifier.rejection(token: String): JwtRejectionReason? =
    when (val result = verifyBlocking(token)) {
        is Result.Ok -> null
        is Result.Err -> (result.error as? JwtVerificationError.InvalidToken)?.reason
    }

private fun claims(block: JWTClaimsSet.Builder.() -> Unit = {}): JWTClaimsSet = validClaims().apply(block).build()

class JwtVerifierSpec :
    FunSpec({
        val verifier = verifier()

        context("正しいトークン") {
            test("RS256 のトークンを通し、スコープと呼び出し元を取り出す") {
                val result = verifier.verifyBlocking(signRsa(claims()))

                val token = result.shouldBeInstanceOf<Result.Ok<VerifiedToken>>().value
                token.issuer shouldBe ISSUER
                token.audience shouldContainExactly listOf(AUDIENCE)
                token.scopes shouldBe setOf("sales.order:read", "sales.order:write")
                token.clientId shouldBe "eiaf-e2e"
                token.expiresAt shouldBe NOW + 5.minutes
            }

            test("ES256 のトークンを通す(既定で受け付けるアルゴリズム)") {
                verifier.rejection(signEc(claims())) shouldBe null
            }

            test("aud が配列で、期待する値を含めば通す") {
                verifier.rejection(signRsa(claims { audience(listOf("other-api", AUDIENCE)) })) shouldBe null
            }

            test("typ が at+jwt(RFC 9068)でも、typ がなくても通す") {
                verifier.rejection(signRsa(claims(), type = JOSEObjectType("at+jwt"))) shouldBe null
                verifier.rejection(signRsa(claims(), type = JOSEObjectType("application/at+jwt"))) shouldBe null
                verifier.rejection(signRsa(claims(), type = null)) shouldBe null
            }

            test("scope がなければスコープは空") {
                val result = verifier.verifyBlocking(signRsa(validClaims(scope = null).build()))
                result.shouldBeInstanceOf<Result.Ok<VerifiedToken>>().value.scopes shouldBe emptySet()
            }

            test("suspend の verify も同じ結果を返す") {
                verifier.verify(signRsa(claims())).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
            }
        }

        context("iss / aud / exp / iat") {
            test("iss が違えば拒否する") {
                verifier.rejection(signRsa(claims { issuer("http://evil.test/realms/eiaf") })) shouldBe JwtRejectionReason.BAD_ISSUER
            }

            test("aud が違えば拒否する") {
                verifier.rejection(signRsa(claims { audience("inventory-api") })) shouldBe JwtRejectionReason.BAD_AUDIENCE
            }

            test("exp を leeway(30 秒)を超えて過ぎたら拒否し、leeway の範囲なら通す") {
                val expired = signRsa(validClaims(now = NOW - 5.minutes - 31.seconds).build())
                val withinLeeway = signRsa(validClaims(now = NOW - 5.minutes - 29.seconds).build())

                verifier.rejection(expired) shouldBe JwtRejectionReason.EXPIRED
                verifier.rejection(withinLeeway) shouldBe null
            }

            test("exp・iat・iss・aud のどれかがなければ拒否する") {
                listOf<JWTClaimsSet.Builder.() -> Unit>(
                    { expirationTime(null) },
                    { issueTime(null) },
                    { issuer(null) },
                    { audience(null as String?) },
                ).forEach { remove ->
                    verifier.rejection(signRsa(claims(remove))) shouldBe JwtRejectionReason.MISSING_CLAIM
                }
            }

            test("nbf と iat が leeway を超えて未来なら拒否する") {
                verifier.rejection(signRsa(claims { notBeforeTime(Date((NOW + 31.seconds).toEpochMilliseconds())) })) shouldBe
                    JwtRejectionReason.NOT_YET_VALID
                verifier.rejection(signRsa(validClaims(now = NOW + 31.seconds).build())) shouldBe JwtRejectionReason.NOT_YET_VALID
                verifier.rejection(signRsa(validClaims(now = NOW + 29.seconds).build())) shouldBe null
            }

            test("exp - iat が上限(既定 1 時間)を超えるトークンを拒否する。上限は設定で変えられる") {
                val longLived = signRsa(validClaims(lifetime = 1.hours + 1.seconds).build())

                verifier.rejection(longLived) shouldBe JwtRejectionReason.LIFETIME_TOO_LONG
                verifier(config = config(maxTokenLifetime = 2.hours)).rejection(longLived) shouldBe null
            }

            test("クレームの型が違えば(exp が文字列など)形式の不正として拒否する") {
                verifier.rejection(signRsa(claims { claim("exp", "tomorrow") })) shouldBe JwtRejectionReason.MALFORMED
                verifier.rejection(signRsa(claims { claim("scope", listOf("a")) })) shouldBe JwtRejectionReason.MALFORMED
            }
        }

        context("署名とアルゴリズム") {
            test("alg=none を拒否する(署名部が空でも、別の署名を付けても)") {
                verifier.rejection(unsigned(claims())) shouldBe JwtRejectionReason.UNSIGNED
                verifier.rejection(noneWithSignature(claims())) shouldBe JwtRejectionReason.MALFORMED
            }

            test("HS256 を拒否する(公開鍵を HMAC の鍵にした、アルゴリズムの混同の攻撃を含む)") {
                verifier.rejection(signHs256WithPublicKey(claims())) shouldBe JwtRejectionReason.DISALLOWED_ALGORITHM
            }

            test("設定にないアルゴリズム(RS384)を拒否する") {
                verifier.rejection(signRsa(claims(), algorithm = JWSAlgorithm.RS384)) shouldBe JwtRejectionReason.DISALLOWED_ALGORITHM
                verifier(config = config(algorithms = setOf("RS384"))).rejection(signRsa(claims(), algorithm = JWSAlgorithm.RS384)) shouldBe
                    null
            }

            test("署名の改竄を拒否する") {
                verifier.rejection(tamperSignature(signRsa(claims()))) shouldBe JwtRejectionReason.BAD_SIGNATURE
            }

            test("ペイロードの改竄(スコープの書き換え)を拒否する") {
                val original = signRsa(validClaims(scope = "sales.order:read").build())
                val elevated = tamperPayload(original, validClaims(scope = "sales.order:read sales.order:write").build())

                verifier.rejection(elevated) shouldBe JwtRejectionReason.BAD_SIGNATURE
            }

            test("JWKS にない kid・別の鍵の署名を拒否する") {
                verifier.rejection(signRsa(claims(), key = TestKeys.rsaUnknown)) shouldBe JwtRejectionReason.UNKNOWN_KEY
                verifier.rejection(signRsa(claims(), key = TestKeys.rsaUnknown, kid = TestKeys.rsa.keyID)) shouldBe
                    JwtRejectionReason.BAD_SIGNATURE
            }

            test("用途が暗号化(use=enc)の鍵では検証しない") {
                val encOnly =
                    RSAKey
                        .Builder(TestKeys.rsa.toRSAPublicKey())
                        .keyID(TestKeys.rsa.keyID)
                        .keyUse(KeyUse.ENCRYPTION)
                        .build()

                verifier(jwks = JWKSet(encOnly)).rejection(signRsa(claims())) shouldBe JwtRejectionReason.UNKNOWN_KEY
            }

            test("typ が JWT / at+jwt 以外なら拒否する") {
                verifier.rejection(signRsa(claims(), type = JOSEObjectType("id_token+jwt"))) shouldBe JwtRejectionReason.BAD_TYPE
            }
        }

        context("形式") {
            test("JWT でない文字列・空・長すぎる文字列を拒否する") {
                listOf("", "abc", "a.b.c", "x".repeat(JwtVerifier.MAX_TOKEN_LENGTH + 1)).forEach {
                    verifier.rejection(it) shouldBe JwtRejectionReason.MALFORMED
                }
            }
        }

        context("JWKS を取得できない") {
            test("鍵を取得できなければ KeysUnavailable(トークンの正否は判断しない)") {
                val failing = JWKSource<SecurityContext> { _, _ -> throw KeySourceException("down") }

                verifier(source = failing).verifyBlocking(signRsa(claims())) shouldBeEqual Result.Err(JwtVerificationError.KeysUnavailable)
            }
        }

        context("設定") {
            test("HS 系・none・未知のアルゴリズムは設定できない") {
                listOf("HS256", "none", "EdDSA", "RSA-OAEP").forEach { alg ->
                    shouldThrow<IllegalArgumentException> { config(algorithms = setOf(alg)) }.message shouldContain "HS 系と none"
                }
            }

            test("leeway は 5 分まで") {
                shouldThrow<IllegalArgumentException> {
                    JwtVerifierConfig(ISSUER, AUDIENCE, JWKS_URI, clockSkew = 6.minutes)
                }
            }
        }

        context("メトリクス") {
            test("拒否した件数を理由ごとに数える") {
                val reader = InMemoryMetricReader.create()
                val counting = verifier(reader = reader)

                counting.verifyBlocking(signRsa(claims { audience("inventory-api") }))
                counting.verifyBlocking(signRsa(claims { audience("inventory-api") }))
                counting.verifyBlocking(unsigned(claims()))
                counting.verifyBlocking(signRsa(claims()))

                val points =
                    reader
                        .collectAllMetrics()
                        .single { it.name == JwtVerifier.REJECTIONS_METRIC }
                        .longSumData.points
                        .associate {
                            it.attributes
                                .asMap()
                                .values
                                .single() to it.value
                        }
                points shouldBe mapOf("bad_audience" to 2L, "unsigned" to 1L)
            }
        }
    })
