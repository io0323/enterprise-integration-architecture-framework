package io.eia.platform.audit

import io.eia.shared.kernel.DomainError

/**
 * 監査の記録・アンカー・検証の失敗。メッセージには記録の中身(details の値など)を入れない。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する(ADR-0011)。
 */
public sealed interface AuditError {
    public val code: String
    public val message: String
}

/** 記録するイベントが不正(長すぎる・制御文字を含むなど)。 */
public data class InvalidAuditEvent(
    public val field: String,
    public val reason: String,
) : AuditError,
    DomainError.NonRetryable {
    override val code: String get() = "audit_invalid_event"
    override val message: String get() = "監査イベントの ${this.field} が不正です: $reason"
}

/** 呼び出し方の誤り(トランザクションの外で呼んだ・分離レベルが READ COMMITTED でないなど)。リトライしても解決しない。 */
public data class AuditMisuse(
    override val message: String,
) : AuditError,
    DomainError.NonRetryable {
    override val code: String get() = "audit_misuse"
}

/** DB・S3 に届かない、または一時的に失敗した。 */
public data class AuditStorageUnavailable(
    public val storage: String,
    public val reason: String,
) : AuditError,
    DomainError.Retryable {
    override val code: String get() = "audit_storage_unavailable"
    override val message: String get() = "$storage に接続できません: $reason"
}

/** DB・S3 が要求を拒否した(権限がない・設定が不正など)。 */
public data class AuditStorageRejected(
    public val storage: String,
    public val reason: String,
) : AuditError,
    DomainError.NonRetryable {
    override val code: String get() = "audit_storage_rejected"
    override val message: String get() = "$storage が要求を拒否しました: $reason"
}
