package io.eia.platform.security.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.util.Base64
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal const val ISSUER = "http://idp.test/realms/eiaf"
internal const val AUDIENCE = "order-api"
internal val JWKS_URI: URI = URI.create("http://idp.test/realms/eiaf/protocol/openid-connect/certs")
internal val NOW: Instant = Instant.parse("2026-09-27T00:00:00Z")

/** テストで何度も使う鍵(RSA 2048 の生成は遅いため、1 回だけ作る)。 */
internal object TestKeys {
    val rsa: RSAKey = rsa("rsa-1")
    val rsaRotated: RSAKey = rsa("rsa-2")
    val rsaUnknown: RSAKey = rsa("rsa-unknown")
    val ec: ECKey =
        ECKeyGenerator(Curve.P_256)
            .keyID("ec-1")
            .keyUse(KeyUse.SIGNATURE)
            .generate()

    fun rsa(kid: String): RSAKey =
        RSAKeyGenerator(2048)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .generate()

    fun jwks(vararg keys: com.nimbusds.jose.jwk.JWK): JWKSet = JWKSet(keys.map { it.toPublicJWK() })

    /** 秘密鍵の PEM(ログに出てはいけない値の例)。 */
    fun privateKeyPem(key: RSAKey = rsa): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.toPrivateKey().encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }
}

/** 正しいクレーム(iss / aud / exp / iat / scope / azp)。各テストはここから 1 か所だけ崩す。 */
internal fun validClaims(
    now: Instant = NOW,
    lifetime: Duration = 5.minutes,
    scope: String? = "sales.order:read sales.order:write",
): JWTClaimsSet.Builder =
    JWTClaimsSet
        .Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject("service-account-eiaf-e2e")
        .claim("azp", "eiaf-e2e")
        .issueTime(Date(now.toEpochMilliseconds()))
        .expirationTime(Date((now + lifetime).toEpochMilliseconds()))
        .jwtID("jti-1")
        .apply { scope?.let { claim("scope", it) } }

internal fun signRsa(
    claims: JWTClaimsSet,
    key: RSAKey = TestKeys.rsa,
    algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
    type: JOSEObjectType? = JOSEObjectType.JWT,
    kid: String? = key.keyID,
): String {
    val header =
        JWSHeader
            .Builder(algorithm)
            .keyID(kid)
            .apply { type?.let(::type) }
            .build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
}

internal fun signEc(
    claims: JWTClaimsSet,
    key: ECKey = TestKeys.ec,
): String =
    SignedJWT(
        JWSHeader
            .Builder(JWSAlgorithm.ES256)
            .keyID(key.keyID)
            .build(),
        claims,
    ).apply { sign(ECDSASigner(key)) }.serialize()

/**
 * アルゴリズムの混同の攻撃: 公開鍵(JWKS で公開されている値)を HMAC の鍵にして HS256 で署名する。
 * 検証側が「トークンの alg に従って、手元の鍵で検証する」実装だと通ってしまう。
 */
internal fun signHs256WithPublicKey(
    claims: JWTClaimsSet,
    key: RSAKey = TestKeys.rsa,
): String =
    SignedJWT(
        JWSHeader
            .Builder(JWSAlgorithm.HS256)
            .keyID(key.keyID)
            .build(),
        claims,
    ).apply { sign(MACSigner(key.toRSAPublicKey().encoded)) }.serialize()

/** `alg=none` の JWT(署名部は空)。 */
internal fun unsigned(claims: JWTClaimsSet): String = PlainJWT(claims).serialize()

/** `alg=none` のヘッダに、別のトークンの署名を付けたもの(署名部を空にしない攻撃)。 */
internal fun noneWithSignature(claims: JWTClaimsSet): String {
    val header = Base64URL.encode("""{"alg":"none","kid":"${TestKeys.rsa.keyID}"}""")
    val payload = Base64URL.encode(claims.toString())
    val signature = signRsa(claims).substringAfterLast('.')
    return "$header.$payload.$signature"
}

/** 署名部の 1 文字を変える。 */
internal fun tamperSignature(token: String): String {
    val signature = token.substringAfterLast('.')
    val replaced = (if (signature[10] == 'A') 'B' else 'A')
    return token.substringBeforeLast('.') + "." + signature.replaceRange(10, 11, replaced.toString())
}

/** ペイロードを差し替え、元の署名を残す(スコープを書き換える攻撃)。 */
internal fun tamperPayload(
    token: String,
    claims: JWTClaimsSet,
): String {
    val (header, _, signature) = token.split('.')
    return "$header.${Base64URL.encode(claims.toString())}.$signature"
}

/** 進められる時計。 */
internal class MutableClock(
    var now: Instant = NOW,
) : Clock {
    override fun now(): Instant = now
}
