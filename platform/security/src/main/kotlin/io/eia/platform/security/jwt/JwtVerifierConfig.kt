package io.eia.platform.security.jwt

import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * JWT(アクセストークン)の検証の設定(Framework 12.1。ADR-0019 §2〜§4)。
 *
 * @param issuer 期待する `iss`(完全一致)。Keycloak では `<frontend の URL>/realms/<realm>`。
 * @param audience 期待する `aud`。トークンの `aud`(文字列または配列)がこの値を含むこと。
 * @param jwksUri 公開鍵(JWKS)の URL。[issuer] とは別に指定する(コンテナ内からはバックチャネルの URL で取得するため。ADR-0019 §3)。
 *   OIDC の discovery は使わない(discovery の応答で取得先が変わると、検証の前提が設定の外で変わるため)。
 * @param algorithms 受け付ける署名アルゴリズム。[SignatureAlgorithms.ALLOWED] の部分集合だけを指定できる。
 * @param clockSkew 時刻のずれの許容幅(leeway)。`exp` / `nbf` / `iat` の判定に使う。
 * @param maxTokenLifetime `exp - iat` の上限。これを超える(長期有効の)トークンを拒否する(Framework 12.1「長期有効JWT禁止」)。
 * @param jwks JWKS の取得とキャッシュ。
 */
@Suppress("LongParameterList") // 設定の項目(既定値つき。名前付き引数で指定する)
public class JwtVerifierConfig(
    public val issuer: String,
    public val audience: String,
    public val jwksUri: URI,
    public val algorithms: Set<String> = SignatureAlgorithms.DEFAULT,
    public val clockSkew: Duration = DEFAULT_CLOCK_SKEW,
    public val maxTokenLifetime: Duration = DEFAULT_MAX_TOKEN_LIFETIME,
    public val jwks: JwksConfig = JwksConfig(),
) {
    init {
        require(issuer.isNotBlank()) { "issuer が空です" }
        require(audience.isNotBlank()) { "audience が空です" }
        require(jwksUri.scheme in setOf("http", "https") && jwksUri.host != null) { "jwksUri は http(s) の絶対 URL にしてください" }
        require(algorithms.isNotEmpty()) { "algorithms が空です" }
        val disallowed = algorithms - SignatureAlgorithms.ALLOWED
        require(disallowed.isEmpty()) {
            "受け付けられない署名アルゴリズムです: $disallowed(使えるのは ${SignatureAlgorithms.ALLOWED}。HS 系と none は使えない。ADR-0019 §2)"
        }
        require(!clockSkew.isNegative() && clockSkew <= MAX_CLOCK_SKEW) { "clockSkew は 0〜$MAX_CLOCK_SKEW にしてください" }
        require(maxTokenLifetime.isPositive()) { "maxTokenLifetime は正の値にしてください" }
    }

    public companion object {
        /** leeway の既定値(ADR-0019 §2)。 */
        public val DEFAULT_CLOCK_SKEW: Duration = 30.seconds

        /** leeway の上限。広げすぎると期限切れのトークンを長く受け入れることになる。 */
        public val MAX_CLOCK_SKEW: Duration = 5.minutes

        /** トークンの有効期間の上限の既定値(Framework 12.1「トークンは短命(≦1h)」。ADR-0019 §2)。 */
        public val DEFAULT_MAX_TOKEN_LIFETIME: Duration = 1.hours
    }
}

/**
 * 署名アルゴリズム(ADR-0019 §2)。
 *
 * 非対称鍵のアルゴリズムだけを受け付ける。HS 系(共通鍵)は、公開鍵を HMAC の鍵にして署名したトークンを
 * 通してしまう「アルゴリズムの混同」の攻撃の入口になり、JWKS(公開鍵)で検証する方式と両立しないため使わない。
 * `none`(署名なし)は常に拒否する。
 */
public object SignatureAlgorithms {
    /** 設定で指定できるアルゴリズムの全体。EdDSA は Nimbus で Tink(任意の依存)が要るため含めない。 */
    public val ALLOWED: Set<String> =
        setOf("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512")

    /** 既定で受け付けるアルゴリズム。Keycloak の既定(RS256)と、RSA-PSS・ECDSA の代表。 */
    public val DEFAULT: Set<String> = setOf("RS256", "PS256", "ES256")
}

/**
 * JWKS の取得とキャッシュの設定(ADR-0019 §3)。
 *
 * @param cacheTtl 取得した JWKS を使い続ける時間。
 * @param refreshAhead 期限のこの時間前からは、リクエストを待たせずに裏で取り直す。
 * @param refreshTimeout 取り直しを待つ時間の上限(ほかのスレッドが取り直している間の待ち時間)。
 * @param rateLimitMinInterval 取り直しの最小の間隔。未知の `kid` のトークンを大量に送られても、IdP に要求が集中しないようにする。
 * @param outageTolerance JWKS を取得できない間、最後に取得した JWKS を使い続ける時間の上限。
 *   鍵の漏洩で鍵を差し替えた直後に JWKS を取得できないと、漏洩した鍵が最長でこの時間受け入れられる(ADR-0019 §3 のトレードオフ)。
 * @param connectTimeout / [readTimeout] JWKS の HTTP の接続と読み取りのタイムアウト。
 * @param sizeLimitBytes JWKS の応答の大きさの上限。
 */
@Suppress("LongParameterList") // 設定の項目(既定値つき。名前付き引数で指定する)
public class JwksConfig(
    public val cacheTtl: Duration = 5.minutes,
    public val refreshAhead: Duration = 30.seconds,
    public val refreshTimeout: Duration = 15.seconds,
    public val rateLimitMinInterval: Duration = 30.seconds,
    public val outageTolerance: Duration = 15.minutes,
    public val connectTimeout: Duration = 2.seconds,
    public val readTimeout: Duration = 2.seconds,
    public val sizeLimitBytes: Int = DEFAULT_SIZE_LIMIT_BYTES,
) {
    init {
        listOf(cacheTtl, refreshAhead, refreshTimeout, rateLimitMinInterval, outageTolerance, connectTimeout, readTimeout)
            .forEach { require(it.isPositive()) { "JwksConfig の時間は正の値にしてください" } }
        // Nimbus の JWKSourceBuilder の制約(満たさないと build 時に例外になるため、設定の時点で分かるようにする)
        require(refreshAhead + refreshTimeout < cacheTtl) { "refreshAhead + refreshTimeout は cacheTtl より短くしてください" }
        require(rateLimitMinInterval < cacheTtl) { "rateLimitMinInterval は cacheTtl より短くしてください" }
        require(outageTolerance >= cacheTtl) { "outageTolerance は cacheTtl 以上にしてください" }
        require(sizeLimitBytes > 0) { "sizeLimitBytes は正の値にしてください" }
    }

    public companion object {
        public const val DEFAULT_SIZE_LIMIT_BYTES: Int = 50 * 1024
    }
}
