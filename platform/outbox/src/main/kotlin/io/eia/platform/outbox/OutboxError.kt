package io.eia.platform.outbox

import io.eia.shared.kernel.DomainError

/**
 * Outbox への追記の失敗。理由に行の値とドライバのメッセージは入れない(制約違反の DETAIL に行の値が含まれるため)。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 */
public sealed interface OutboxError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is OutboxMisuse -> this
            is OutboxStorageUnavailable -> this
            is OutboxStorageRejected -> this
        }
}

/** 使い方の誤り(トランザクションの外で呼んだ・JDBC の接続を取れないなど)。 */
public data class OutboxMisuse(
    override val message: String,
) : OutboxError,
    DomainError.NonRetryable {
    override val code: String get() = "outbox_misuse"
}

/** DB が一時的に使えない(接続・ロールバック・資源不足・運用者の介入。SQLSTATE のクラス 08 / 40 / 53 / 57)。 */
public data class OutboxStorageUnavailable(
    public val reason: String,
) : OutboxError,
    DomainError.Retryable {
    override val code: String get() = "outbox_storage_unavailable"
    override val message: String get() = "Outbox に書き込めません($reason)"
}

/** DB が書き込みを拒否した(制約違反・権限など)。リトライしても解決しない。 */
public data class OutboxStorageRejected(
    public val reason: String,
) : OutboxError,
    DomainError.NonRetryable {
    override val code: String get() = "outbox_storage_rejected"
    override val message: String get() = "Outbox への書き込みを拒否されました($reason)"
}
