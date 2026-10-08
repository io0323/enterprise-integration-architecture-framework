package io.eia.legacyorderacl.application.port.inbound

import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * レガシーの受注表の 1 つの変更を変換し、注文のヘッダの最新の状態として発行する(Anti-Corruption Layer。ADR-0026)。
 *
 * - 登録・更新・Snapshot の読み取り([LegacyOrderChange.Upsert])は、変換した状態を発行する。
 * - 削除([LegacyOrderChange.Delete])は、注文番号の tombstone を発行する。
 *
 * @return 発行した結果。変換できない値は `TranslationError`(NonRetryable。呼び出し元が DLQ に送る)、
 *   発行の失敗は `LegacyOrderStatePublisher` のエラー(Retryable なら呼び出し元が読み直す)
 */
public interface TranslateLegacyOrderChangeUseCase {
    public suspend operator fun invoke(change: LegacyOrderChange): Result<TranslationOutcome, DomainError>
}

/** レガシーの受注表の 1 つの変更(生の CDC の 1 レコード)。 */
public sealed interface LegacyOrderChange {
    public val position: ChangePosition

    /** 登録・更新・Snapshot の読み取り。[row] は変更の後の行。 */
    public data class Upsert(
        val row: LegacyOrderRow,
        override val position: ChangePosition,
    ) : LegacyOrderChange

    /** 削除。[orderNumber] は変更の前の行の注文番号(レガシーの固定長の形式のまま)。 */
    public data class Delete(
        val orderNumber: String,
        override val position: ChangePosition,
    ) : LegacyOrderChange
}

/**
 * 変更の位置(出力の `source`。ADR-0026 §6・§9)。
 *
 * @property lsn PostgreSQL の WAL の位置(Debezium の `source.lsn`)
 * @property committedAt レガシーの DB でコミットされた時刻(Snapshot では読み取った時刻)
 * @property snapshot Snapshot(初回・Incremental)で読んだ変更なら true
 */
public data class ChangePosition(
    val lsn: Long,
    val committedAt: Instant,
    val snapshot: Boolean,
)

/** 発行した結果(メトリクスの `outcome`)。 */
public enum class TranslationOutcome {
    UPSERTED,
    DELETED,
}
