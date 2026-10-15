package io.eia.platform.filetransfer

import io.eia.shared.kernel.DomainError

/**
 * ファイル連携の部品の失敗。理由に業務の値・資格情報・応答の本文は入れない。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 * 受け取ったファイルの不合格(改竄・欠損など)は失敗ではなく、検査の結果([io.eia.platform.filetransfer.verify.Verification.Rejected])で返す。
 */
public sealed interface FileTransferError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is StoreUnavailable -> this
            is StoreRejected -> this
            is FileTransferMisuse -> this
            is LocalFileFailed -> this
            is MoveIncomplete -> this
            is ObjectMissing -> this
        }
}

/** 置き場(S3 互換ストレージ)が一時的に使えない(接続・5xx・429・資格情報を取れない)。 */
public data class StoreUnavailable(
    public val reason: String,
) : FileTransferError,
    DomainError.Retryable {
    override val code: String get() = "file_store_unavailable"
    override val message: String get() = "ファイルの置き場を使えません($reason)"
}

/** 置き場が拒否した(権限・バケットがないなど)。 */
public data class StoreRejected(
    public val reason: String,
) : FileTransferError,
    DomainError.NonRetryable {
    override val code: String get() = "file_store_rejected"
    override val message: String get() = "ファイルの置き場に拒否されました($reason)"
}

/** 使い方の誤り(行の値の型・列の数・名前の形式など)。 */
public data class FileTransferMisuse(
    override val message: String,
) : FileTransferError,
    DomainError.NonRetryable {
    override val code: String get() = "file_transfer_misuse"
}

/** 一時ファイル(書き出し・検査の作業場所)を読み書きできない。 */
public data class LocalFileFailed(
    public val reason: String,
) : FileTransferError,
    DomainError.Retryable {
    override val code: String get() = "file_local_failed"
    override val message: String get() = "一時ファイルを読み書きできません($reason)"
}

/**
 * 移動を続けられない(移動元にも移動先にもデータファイルがない、など)。やり直しても直らないので、運用に引き継ぐ。
 * 移動の途中で落ちたときは、この失敗ではなく、同じ移動をもう一度呼べば続きから終わる([io.eia.platform.filetransfer.move.FileMover])。
 */
public data class MoveIncomplete(
    public val reason: String,
) : FileTransferError,
    DomainError.NonRetryable {
    override val code: String get() = "file_move_incomplete"
    override val message: String get() = "ファイルを移動できません($reason)"
}

/** 検査を頼まれたマニフェストが置き場にない(一覧の後に消された・移動の済んだものをもう一度頼んだ)。 */
public data class ObjectMissing(
    public val key: String,
) : FileTransferError,
    DomainError.NonRetryable {
    override val code: String get() = "file_object_missing"
    override val message: String get() = "$key がありません"
}
