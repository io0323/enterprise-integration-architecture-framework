package io.eia.platform.security.token

import io.eia.platform.security.secret.SecretError
import io.eia.shared.kernel.DomainError
import kotlin.time.Duration

/**
 * トークンを取得できなかった理由。メッセージには HTTP のステータスと OAuth のエラーコードだけを入れ、
 * 応答の本文・Client Secret・トークンは入れない。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する。
 * リトライ([DomainError.Retryable] と kernel の RetryPolicy)は P04b の `platform/reliability` で結線する。
 */
public sealed interface TokenError {
    public val code: String
    public val message: String
}

/**
 * トークンエンドポイントが一時的に使えない(タイムアウト・接続の失敗・408・429・5xx)。
 *
 * @param reason `timeout` / `connection` / `rate_limited` / `server_error` / `request_timeout`
 * @param retryAfter 429 / 503 の `Retry-After`(秒数または HTTP-date)。なければ null
 */
public data class TokenEndpointUnavailable(
    public val reason: String,
    public val status: Int? = null,
    override val retryAfter: Duration? = null,
) : TokenError,
    DomainError.Retryable {
    override val code: String get() = "token_endpoint_unavailable"
    override val message: String get() = "トークンエンドポイントが一時的に使えません($reason${status?.let { ", status=$it" }.orEmpty()})"
}

/**
 * トークンの要求を拒否された(400・401・403 などの 4xx)。Client Secret の誤りやスコープの不許可など、リトライしても解決しない。
 *
 * @param error OAuth のエラーコード(`invalid_client` など。RFC 6749 §5.2)。形式が不正なら null
 */
public data class TokenRequestRejected(
    public val status: Int,
    public val error: String?,
) : TokenError,
    DomainError.NonRetryable {
    override val code: String get() = "token_request_rejected"
    override val message: String get() = "トークンの要求を拒否されました(status=$status${error?.let { ", error=$it" }.orEmpty()})"
}

/** 応答が RFC 6749 §5.1 の形式ではない(`access_token` がない・`token_type` が Bearer でないなど)。契約の違反。 */
public data class InvalidTokenResponse(
    public val reason: String,
) : TokenError,
    DomainError.NonRetryable {
    override val code: String get() = "invalid_token_response"
    override val message: String get() = "トークンの応答が不正です($reason)"
}

/** Client Secret を取得できない。[cause] が一時的な失敗([DomainError.Retryable])かどうかで、実装を分ける。 */
public sealed interface ClientSecretUnavailable : TokenError {
    public val cause: SecretError

    public data class Permanent(
        override val cause: SecretError,
    ) : ClientSecretUnavailable,
        DomainError.NonRetryable {
        override val code: String get() = "client_secret_unavailable"
        override val message: String get() = cause.message
    }

    public data class Temporary(
        override val cause: SecretError,
    ) : ClientSecretUnavailable,
        DomainError.Retryable {
        override val code: String get() = "client_secret_unavailable"
        override val message: String get() = cause.message
    }

    public companion object {
        public fun of(cause: SecretError): ClientSecretUnavailable =
            if (cause is DomainError.Retryable) Temporary(cause) else Permanent(cause)
    }
}
