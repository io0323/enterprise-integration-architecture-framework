package io.eia.legacyorderacl.application.port.outbound

import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Duration

/**
 * 照合の範囲。[All] は全件、[Keys] は指定した注文番号だけ(比べ直し)。
 */
public sealed interface ReconcileScope {
    public data object All : ReconcileScope

    public data class Keys(
        val orderNumbers: Set<String>,
    ) : ReconcileScope
}

/**
 * レガシーの受注表(読み取り専用。ADR-0027 の例外)。
 */
public interface LegacySource {
    /**
     * 1 つのスナップショットで、[scope] の行と、そのスナップショットの位置を読む。**読み終えたらトランザクションを閉じてから戻る**
     * (待ちの間に古いスナップショットを残さない。レガシーの DB の VACUUM を止めないため)。
     */
    public suspend fun read(scope: ReconcileScope): Result<SourceSnapshot, DomainError>

    /** CDC(レプリケーションスロット)が [position] より先まで取り込み終えるのを待つ(上限を超えたら Retryable の Err)。 */
    public suspend fun awaitCaptured(position: String): Result<Unit, DomainError>
}

/**
 * @property position スナップショットの位置(PostgreSQL の LSN。例 `0/1A2B3C4`)
 * @property rows 行(レガシーの形式のまま)
 */
public data class SourceSnapshot(
    val position: String,
    val rows: List<LegacyOrderRow>,
)

/** 整形済みのトピックの最新の状態。 */
public interface PublishedLegacyOrders {
    /**
     * ACL が、今の生の CDC の末尾まで処理し終えるのを待ってから、[scope] のキーの最新の状態を読む(上限を超えたら Retryable の Err)。
     * 値は契約から作った [LegacyOrder]。tombstone(削除)のキーは含めない。
     */
    public suspend fun readCaughtUp(scope: ReconcileScope): Result<Map<String, LegacyOrder>, DomainError>
}

/** 正規の文字列の SHA-256(16 進)。 */
public fun interface Fingerprints {
    public fun sha256(canonical: String): String
}

/** 比べ直しの前に待つ。 */
public fun interface Pause {
    public suspend fun pause(duration: Duration)
}
