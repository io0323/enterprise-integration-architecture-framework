package io.eia.platform.inbox

import io.eia.shared.kernel.DomainError

/**
 * 冪等消費の記録の失敗。理由に行の値とドライバのメッセージは入れない(制約違反の DETAIL に行の値が含まれるため)。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 */
public sealed interface InboxError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is InboxMisuse -> this
            is InboxStorageUnavailable -> this
            is InboxStorageRejected -> this
        }
}

/** 使い方の誤り(トランザクションの外で呼んだ・JDBC の接続を取れない・引数の形式など)。 */
public data class InboxMisuse(
    override val message: String,
) : InboxError,
    DomainError.NonRetryable {
    override val code: String get() = "inbox_misuse"
}

/** DB が一時的に使えない(接続・ロールバック・資源不足・運用者の介入。SQLSTATE のクラス 08 / 40 / 53 / 57)。 */
public data class InboxStorageUnavailable(
    public val reason: String,
) : InboxError,
    DomainError.Retryable {
    override val code: String get() = "inbox_storage_unavailable"
    override val message: String get() = "冪等消費の記録を書けません($reason)"
}

/** DB が書き込みを拒否した(権限・制約違反など)。リトライしても解決しない。 */
public data class InboxStorageRejected(
    public val reason: String,
) : InboxError,
    DomainError.NonRetryable {
    override val code: String get() = "inbox_storage_rejected"
    override val message: String get() = "冪等消費の記録を拒否されました($reason)"
}
