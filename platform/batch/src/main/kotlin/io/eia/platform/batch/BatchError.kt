package io.eia.platform.batch

import io.eia.shared.kernel.DomainError
import kotlin.time.Duration

/**
 * バッチの実行の失敗。理由に業務の値とドライバのメッセージは入れない。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 */
public sealed interface BatchError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is BatchMisuse -> this
            is BatchStorageUnavailable -> this
            is BatchStorageRejected -> this
            is BatchSourceUnavailable -> this
            is BatchSourceRejected -> this
            is BatchRunLocked -> this
            is BatchStepFailed -> this
        }
}

/** 使い方の誤り(引数の形式など)。 */
public data class BatchMisuse(
    override val message: String,
) : BatchError,
    DomainError.NonRetryable {
    override val code: String get() = "batch_misuse"
}

/** 実行の記録の DB が一時的に使えない(SQLSTATE のクラス 08 / 40 / 53 / 57)。 */
public data class BatchStorageUnavailable(
    public val reason: String,
) : BatchError,
    DomainError.Retryable {
    override val code: String get() = "batch_storage_unavailable"
    override val message: String get() = "バッチの実行の記録を読み書きできません($reason)"
}

/** 実行の記録の DB が拒否した(権限・制約違反など)。 */
public data class BatchStorageRejected(
    public val reason: String,
) : BatchError,
    DomainError.NonRetryable {
    override val code: String get() = "batch_storage_rejected"
    override val message: String get() = "バッチの実行の記録を拒否されました($reason)"
}

/** ファイルの置き場(S3 互換ストレージ)が一時的に使えない(接続・5xx・429)。 */
public data class BatchSourceUnavailable(
    public val reason: String,
) : BatchError,
    DomainError.Retryable {
    override val code: String get() = "batch_source_unavailable"
    override val message: String get() = "ファイルの置き場を読めません($reason)"
}

/** ファイルの置き場が拒否した(権限・バケットがないなど)。 */
public data class BatchSourceRejected(
    public val reason: String,
) : BatchError,
    DomainError.NonRetryable {
    override val code: String get() = "batch_source_rejected"
    override val message: String get() = "ファイルの置き場に拒否されました($reason)"
}

/** 同じ実行(DAG とパーティション)を、ほかのインスタンスが実行している。 */
public data class BatchRunLocked(
    public val run: RunKey,
) : BatchError,
    DomainError.NonRetryable {
    override val code: String get() = "batch_run_locked"
    override val message: String get() = "$run はほかのインスタンスが実行中です"
}

/**
 * ステップがリトライの後も失敗した(運用に引き継ぐ。Framework 7.4)。成功したステップの結果と Checkpoint は残り、
 * 同じパーティションを再実行すると、このステップから再開する。
 *
 * @property cause 最後の試行の失敗。メッセージには失敗のコードだけを入れる
 */
public data class BatchStepFailed(
    public val run: RunKey,
    public val step: StepName,
    public val attempts: Int,
    public val cause: DomainError,
) : BatchError,
    DomainError.NonRetryable {
    override val code: String get() = "batch_step_failed"
    override val message: String get() = "$run のステップ $step が $attempts 回失敗しました(${cause.code})"
}

/** ステップが例外を投げた。リトライできる失敗として扱う(バッチは再実行安全に作るので、やり直しても害がない)。 */
public data class StepThrew(
    public val exception: String,
) : DomainError.Retryable {
    override val code: String get() = "step_threw"
    override val message: String get() = "ステップが例外を投げました($exception)"
}

/** ステップの 1 回の試行が上限の時間を超えた。 */
public data class StepTimedOut(
    public val timeout: Duration,
) : DomainError.Retryable {
    override val code: String get() = "step_timed_out"
    override val message: String get() = "ステップが $timeout を超えました"
}
