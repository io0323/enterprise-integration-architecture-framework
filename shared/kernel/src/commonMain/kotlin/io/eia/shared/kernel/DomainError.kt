package io.eia.shared.kernel

import kotlin.time.Duration

/**
 * 業務・連携のエラー。直下の子は [Retryable] と [NonRetryable] の 2 つだけで、`when` はこの 2 分岐で網羅できる(ADR-0011)。
 *
 * 各サービスは自分のエラーを [Retryable] か [NonRetryable] のどちらか一方を実装して定義する。
 * 両方を実装する型は Konsist で禁止している(tools/architecture-test)。
 */
public sealed interface DomainError {
    /** 機械可読なエラーコード(snake_case)。Problem Details の `type` や DLQ のヘッダに使う。 */
    public val code: String

    /** 人が読むための説明。ペイロードや個人情報を含めない。 */
    public val message: String

    /** 一時的な失敗。リトライで解決しうる(408 / 429 / 5xx・接続断など)。[RetryPolicy] のリトライ対象。 */
    public interface Retryable : DomainError {
        /** 相手が指定した再試行までの待ち時間(429 / 503 の Retry-After)。指定がなければ null。 */
        public val retryAfter: Duration? get() = null
    }

    /** 恒久的な失敗。リトライしても解決しない(4xx 系・契約違反・業務エラー)。DLQ / エラー処理へ直行する。 */
    public interface NonRetryable : DomainError
}

/** 入力の 1 項目に対する違反。[field] は項目のパス(例: `lines[0].quantity`)。 */
public data class FieldViolation(
    public val field: String,
    public val reason: String,
)

/** 構造・値域・業務整合の検証エラー(Framework 15.2)。 */
public data class ValidationError(
    public val violations: List<FieldViolation>,
) : DomainError.NonRetryable {
    override val code: String get() = "validation_failed"
    override val message: String get() = violations.joinToString("; ") { "${it.field}: ${it.reason}" }

    public companion object {
        public fun of(
            field: String,
            reason: String,
        ): ValidationError = ValidationError(listOf(FieldViolation(field, reason)))
    }
}

public data class NotFoundError(
    public val resource: String,
    public val id: String,
) : DomainError.NonRetryable {
    override val code: String get() = "not_found"
    override val message: String get() = "$resource '$id' が見つかりません"
}

public data class ConflictError(
    override val message: String,
) : DomainError.NonRetryable {
    override val code: String get() = "conflict"
}

/** 依存先が一時的に利用できない(タイムアウト・接続断・503 など)。 */
public data class UnavailableError(
    override val message: String,
    override val retryAfter: Duration? = null,
) : DomainError.Retryable {
    override val code: String get() = "unavailable"
}

/** 分類できない例外。リトライしても解決する保証がないため [DomainError.NonRetryable] とする。 */
public data class UnexpectedError(
    override val message: String,
    public val cause: Throwable? = null,
) : DomainError.NonRetryable {
    override val code: String get() = "unexpected"

    public companion object {
        public fun from(cause: Throwable): UnexpectedError = UnexpectedError(cause::class.simpleName ?: "unknown", cause)
    }
}
