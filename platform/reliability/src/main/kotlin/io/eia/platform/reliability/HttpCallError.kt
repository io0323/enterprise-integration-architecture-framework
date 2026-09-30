package io.eia.platform.reliability

import io.eia.shared.kernel.DomainError
import kotlin.time.Duration

/**
 * HTTP の呼び出しの失敗([HttpCallClassifier] の結果)。メッセージにはステータスと理由だけを入れ、
 * URL・ヘッダ・応答の本文・例外のメッセージは入れない(URL やトークンを含みうるため)。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する(ADR-0011)。
 */
public sealed interface HttpCallError {
    /** 応答のステータス。応答を受け取れなかったとき(接続の失敗・タイムアウト)は null。 */
    public val status: Int?

    /** 失敗の理由。`status` / `connection` / `timeout` のどれか(メトリクスの属性に使える、数が限られた値)。 */
    public val reason: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is HttpCallUnavailable -> this
            is HttpCallRejected -> this
        }
}

/**
 * 依存先が一時的に使えない(408・429・502・503・504・接続の失敗・タイムアウト。INTEGRATION_STANDARDS §3)。
 * Circuit Breaker の失敗に数え、リトライの対象にする(ADR-0021 §3)。
 *
 * @param retryAfter 429 / 503 の `Retry-After`。なければ null
 */
public data class HttpCallUnavailable(
    override val status: Int?,
    override val reason: String,
    override val retryAfter: Duration? = null,
) : HttpCallError,
    DomainError.Retryable {
    override val code: String get() = "http_call_unavailable"
    override val message: String
        get() = "依存先が一時的に使えません($reason${status?.let { ", status=$it" }.orEmpty()})"
}

/**
 * 依存先が要求を拒否した(リトライ対象でない 4xx・5xx。500 を含む。INTEGRATION_STANDARDS §3)。
 * 依存先は応答しているので、Circuit Breaker の成功に数え、リトライしない(ADR-0021 §3)。
 */
public data class HttpCallRejected(
    override val status: Int,
) : HttpCallError,
    DomainError.NonRetryable {
    override val reason: String get() = HttpCallClassifier.REASON_STATUS
    override val code: String get() = "http_call_rejected"
    override val message: String get() = "依存先が要求を拒否しました(status=$status)"
}
