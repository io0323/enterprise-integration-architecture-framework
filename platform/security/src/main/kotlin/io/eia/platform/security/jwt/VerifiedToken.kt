package io.eia.platform.security.jwt

import io.eia.shared.kernel.DomainError
import kotlin.time.Instant

/**
 * 検証を通ったアクセストークンの内容。Ktor の principal として使う([io.eia.platform.security.ktor.eiaJwt])。
 *
 * トークンの文字列そのものは持たない(ログや例外に紛れ込ませないため)。
 *
 * @param subject `sub`(Client Credentials ではサービスアカウントの ID)
 * @param clientId `azp`(なければ `client_id`)。呼び出し元のクライアント
 * @param scopes `scope`(空白区切り)を分けた集合
 */
public data class VerifiedToken(
    public val issuer: String,
    public val subject: String?,
    public val clientId: String?,
    public val audience: List<String>,
    public val scopes: Set<String>,
    public val issuedAt: Instant,
    public val expiresAt: Instant,
    public val tokenId: String?,
) {
    public fun hasScopes(required: Collection<String>): Boolean = scopes.containsAll(required)
}

/**
 * JWT の検証の失敗。
 *
 * 理由([JwtRejectionReason])はログとメトリクスにだけ使い、クライアントには返さない(ADR-0019 §5)。
 * メッセージにはトークン・クレームの値・ライブラリの例外のメッセージを入れない。
 */
public sealed interface JwtVerificationError {
    public val code: String
    public val message: String

    /** トークンが不正。401 を返す。 */
    public data class InvalidToken(
        public val reason: JwtRejectionReason,
    ) : JwtVerificationError,
        DomainError.NonRetryable {
        override val code: String get() = "invalid_token"
        override val message: String get() = "アクセストークンを拒否しました(${reason.code})"
    }

    /** 公開鍵(JWKS)を取得できず、検証できない。トークンの正否は分からないため 503 を返す(ADR-0019 §5)。 */
    public data object KeysUnavailable : JwtVerificationError, DomainError.Retryable {
        override val code: String get() = "jwks_unavailable"
        override val message: String get() = "JWKS を取得できないため、アクセストークンを検証できません"
    }
}

/** トークンを拒否した理由。[code] をログとメトリクスの属性に使う(カーディナリティは固定)。 */
public enum class JwtRejectionReason(
    public val code: String,
) {
    /** JWT の形式ではない・JSON が壊れている・クレームの型が違う・大きすぎる。 */
    MALFORMED("malformed"),

    /** 署名がない(`alg=none`)。 */
    UNSIGNED("unsigned"),

    /** 暗号化された JWT(JWE)。アクセストークンとしては受け付けない。 */
    ENCRYPTED("encrypted"),

    /** 受け付けない署名アルゴリズム(HS 系・設定にないアルゴリズム)。 */
    DISALLOWED_ALGORITHM("disallowed_alg"),

    /** ヘッダの `typ` が `JWT` / `at+jwt` / なし のどれでもない。 */
    BAD_TYPE("bad_type"),

    /** `kid` とアルゴリズムに合う公開鍵が JWKS にない(取り直しても見つからない)。 */
    UNKNOWN_KEY("unknown_key"),

    /** 署名が一致しない(改竄・別の鍵での署名)。 */
    BAD_SIGNATURE("bad_signature"),

    /** 必須のクレーム(`iss` / `aud` / `exp` / `iat`)がない。 */
    MISSING_CLAIM("missing_claim"),
    BAD_ISSUER("bad_issuer"),
    BAD_AUDIENCE("bad_audience"),

    /** `exp` を leeway を超えて過ぎた。 */
    EXPIRED("expired"),

    /** `nbf` / `iat` が leeway を超えて未来。 */
    NOT_YET_VALID("not_yet_valid"),

    /** `exp - iat` が上限を超える(長期有効のトークン)。 */
    LIFETIME_TOO_LONG("lifetime_too_long"),
}
