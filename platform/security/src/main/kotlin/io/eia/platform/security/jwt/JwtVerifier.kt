package io.eia.platform.security.jwt

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.EncryptedJWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import io.opentelemetry.api.metrics.Meter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.security.Key
import java.text.ParseException
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * アクセストークン(JWT)を検証する(Framework 12.1。CLAUDE.md §5「JWT は iss・aud・exp 検証」。ADR-0019)。
 *
 * 次の順に検証し、最初に失敗した段の理由([JwtRejectionReason])で拒否する。
 * 1. 形式: JWS であること(`alg=none` の JWT と JWE は拒否する)。大きさの上限([MAX_TOKEN_LENGTH])。
 * 2. ヘッダ: `alg` が [JwtVerifierConfig.algorithms] に含まれること。`typ` が `JWT` / `at+jwt` / `application/at+jwt` / なし であること。
 * 3. 鍵: JWKS から `kid`・アルゴリズム・用途(`use=sig`)に合う公開鍵を選ぶ。未知の `kid` なら JWKS を取り直す(鍵のローテーション)。
 * 4. 署名: 選んだ鍵で検証する。
 * 5. クレーム: `iss`(完全一致)・`aud`(含む)・`exp`・`iat` は必須。`nbf` はあれば検証する。`exp - iat` の上限。
 *
 * 署名を確かめる前にクレームを判定しない(署名のないトークンに対して、クレームの判定結果を観測させないため)。
 *
 * - 拒否した理由は DEBUG ログとメトリクス(`eia.security.jwt.rejections`、属性 `reason`)にだけ残す。
 *   トークン・クレームの値・Nimbus の例外のメッセージ(ヘッダやクレームの値を含むことがある)は、ログにも戻り値にも入れない。
 * - JWKS の取得はブロッキングの I/O のため、[verify] は [Dispatchers.IO] で実行する。
 * - JWKS のキャッシュ(バックグラウンドの取り直しを含む)を持つため、使い終わったら [close] する。
 */
public class JwtVerifier internal constructor(
    private val config: JwtVerifierConfig,
    private val jwkSource: JWKSource<SecurityContext>,
    private val clock: Clock,
    meter: Meter?,
) : AutoCloseable {
    /**
     * @param clock `exp` などの判定に使う時刻(テストでは固定の時刻を渡す)
     * @param meter 拒否の件数を数える Meter(`ObservabilityRuntime.meter` など)。null なら数えない
     */
    public constructor(
        config: JwtVerifierConfig,
        clock: Clock = Clock.System,
        meter: Meter? = null,
    ) : this(config, retriever(config.jwks), clock, meter)

    internal constructor(
        config: JwtVerifierConfig,
        retriever: ResourceRetriever,
        clock: Clock,
        meter: Meter?,
    ) : this(config, jwkSource(config, retriever), clock, meter)

    private val algorithms: Set<JWSAlgorithm> = config.algorithms.map(JWSAlgorithm::parse).toSet()
    private val keySelector = JWSVerificationKeySelector(algorithms, jwkSource)
    private val verifierFactory = DefaultJWSVerifierFactory()

    /**
     * 直前の鍵の取得が失敗したか(JWKS を取得できず、使える JWKS もなかったか)。
     * Nimbus の取り直しの頻度の制限は、失敗した取得も 1 回と数える。IdP が止まっている間に制限にかかったリクエストを、
     * 鍵が見つからない(401)ではなく検証できない(503)にするために使う。
     */
    @Volatile
    private var keysUnavailable: Boolean = false
    private val recorder = JwtRejectionRecorder(meter)

    /** [token](`Authorization: Bearer` の値)を検証する。 */
    public suspend fun verify(token: String): Result<VerifiedToken, JwtVerificationError> =
        withContext(Dispatchers.IO) { verifyBlocking(token) }

    internal fun verifyBlocking(token: String): Result<VerifiedToken, JwtVerificationError> {
        val result = check(token)
        if (result is Result.Err) recorder.record(result.error)
        return result
    }

    /** 段ごとに検証し、最初に失敗した段の理由で拒否する(理由を 1 つに決めるため)。 */
    private fun check(token: String): Result<VerifiedToken, JwtVerificationError> =
        parse(token)
            .flatMap(::checkHeader)
            .flatMap(::checkSignature)
            .flatMap { TokenClaims.read(it) }
            .flatMap(::checkClaims)

    /** 1. 形式: JWS であること。 */
    private fun parse(token: String): Result<SignedJWT, JwtVerificationError> {
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return reject(JwtRejectionReason.MALFORMED)
        val jwt =
            try {
                JWTParser.parse(token)
            } catch (_: ParseException) {
                null
            }
        return when (jwt) {
            is SignedJWT -> ok(jwt)
            is PlainJWT -> reject(JwtRejectionReason.UNSIGNED)
            is EncryptedJWT -> reject(JwtRejectionReason.ENCRYPTED)
            else -> reject(JwtRejectionReason.MALFORMED)
        }
    }

    /** 2. ヘッダ: 許可したアルゴリズムと typ。 */
    private fun checkHeader(jwt: SignedJWT): Result<SignedJWT, JwtVerificationError> {
        val header = jwt.header
        return when {
            header.algorithm !in algorithms -> reject(JwtRejectionReason.DISALLOWED_ALGORITHM)
            header.type != null && header.type !in ACCEPTED_TYPES -> reject(JwtRejectionReason.BAD_TYPE)
            else -> ok(jwt)
        }
    }

    /** 3・4. 鍵を選び、署名を検証する。通ったらクレームを取り出す。 */
    private fun checkSignature(jwt: SignedJWT): Result<JWTClaimsSet, JwtVerificationError> {
        val keys = selectKeys(jwt) ?: return err(JwtVerificationError.KeysUnavailable)
        return when {
            keys.isEmpty() -> {
                reject(JwtRejectionReason.UNKNOWN_KEY)
            }

            keys.none { verifies(jwt, it) } -> {
                reject(JwtRejectionReason.BAD_SIGNATURE)
            }

            else -> {
                try {
                    ok(jwt.jwtClaimsSet)
                } catch (_: ParseException) {
                    reject(JwtRejectionReason.MALFORMED)
                }
            }
        }
    }

    /** JWKS から鍵を選ぶ。JWKS を取得できず検証できないときは null。 */
    private fun selectKeys(jwt: SignedJWT): List<Key>? =
        try {
            keySelector.selectJWSKeys(jwt.header, null).also { keysUnavailable = false }
        } catch (_: RateLimitReachedException) {
            // 取り直そうとしたが、最小の間隔の中だった(KeySourceException の子なので先に捕まえる)。
            // - 直前の取得が失敗していた(IdP が止まり、使える JWKS がない): 検証できないので null(503)
            // - そうでなければ、手元の JWKS に鍵がない: 空(401。未知の kid を送るだけで 503 を返させないため)
            if (keysUnavailable) null else emptyList()
        } catch (_: KeySourceException) {
            keysUnavailable = true
            null
        }

    private fun verifies(
        jwt: SignedJWT,
        key: Key,
    ): Boolean =
        try {
            jwt.verify(verifierFactory.createJWSVerifier(jwt.header, key))
        } catch (_: JOSEException) {
            // 鍵の型とアルゴリズムが合わないなど。この鍵では検証できない
            false
        }

    /**
     * 5. クレーム: iss・aud・時刻・有効期間、設定によって呼び出し元のクライアント(必須のクレームの有無と型は [TokenClaims.read] で
     * 確かめ済み)。
     */
    private fun checkClaims(claims: TokenClaims): Result<VerifiedToken, JwtVerificationError> {
        val now = clock.now()
        val skew = config.clockSkew
        val (iat, exp, nbf) = claims.validity
        val reason =
            when {
                claims.issuer != config.issuer -> JwtRejectionReason.BAD_ISSUER

                config.audience !in claims.audience -> JwtRejectionReason.BAD_AUDIENCE

                // RFC 7519 §4.1.4: 現在時刻が exp より前であること。leeway の分だけ遅らせる
                now >= exp + skew -> JwtRejectionReason.EXPIRED

                nbf != null && now + skew < nbf -> JwtRejectionReason.NOT_YET_VALID

                now + skew < iat -> JwtRejectionReason.NOT_YET_VALID

                exp <= iat -> JwtRejectionReason.MALFORMED

                exp - iat > config.maxTokenLifetime -> JwtRejectionReason.LIFETIME_TOO_LONG

                config.requireClientId && claims.caller.clientId.isNullOrBlank() -> JwtRejectionReason.MISSING_CLIENT_ID

                else -> null
            }
        return if (reason != null) reject(reason) else ok(claims.toVerifiedToken())
    }

    override fun close() {
        (jwkSource as? Closeable)?.close()
    }

    public companion object {
        /** トークンの文字列の長さの上限。解析の前に弾き、巨大な入力の解析に時間とメモリを使わせない。 */
        public const val MAX_TOKEN_LENGTH: Int = 8 * 1024

        public const val REJECTIONS_METRIC: String = JwtRejectionRecorder.METRIC

        /** RFC 9068 §2.1 の `at+jwt`(`application/at+jwt` も可)と、Keycloak などが付ける `JWT`。 */
        private val ACCEPTED_TYPES = setOf(JOSEObjectType.JWT, JOSEObjectType("at+jwt"), JOSEObjectType("application/at+jwt"))

        private fun retriever(jwks: JwksConfig): ResourceRetriever =
            DefaultResourceRetriever(
                jwks.connectTimeout.millisInt(),
                jwks.readTimeout.millisInt(),
                jwks.sizeLimitBytes,
            )

        internal fun jwkSource(
            config: JwtVerifierConfig,
            retriever: ResourceRetriever,
        ): JWKSource<SecurityContext> {
            val jwks = config.jwks
            return JWKSourceBuilder
                .create<SecurityContext>(config.jwksUri.toURL(), retriever)
                .cache(jwks.cacheTtl.inWholeMilliseconds, jwks.refreshTimeout.inWholeMilliseconds)
                .refreshAheadCache(jwks.refreshAhead.inWholeMilliseconds, false)
                .rateLimited(jwks.rateLimitMinInterval.inWholeMilliseconds)
                .outageTolerant(jwks.outageTolerance.inWholeMilliseconds)
                .retrying(false)
                .build()
        }

        private fun Duration.millisInt(): Int = inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

private fun reject(reason: JwtRejectionReason): Result<Nothing, JwtVerificationError> = err(JwtVerificationError.InvalidToken(reason))
