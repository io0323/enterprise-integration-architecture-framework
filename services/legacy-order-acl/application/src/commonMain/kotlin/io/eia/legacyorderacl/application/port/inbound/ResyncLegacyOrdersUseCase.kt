package io.eia.legacyorderacl.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 照合で見つけたずれ([ReconciliationReport.drift])を直す(部分の再同期。ADR-0027 §6)。
 *
 * - ずれのキーが上限を超えたら、**一部だけを取り直すこともせず、何もしない**([ResyncResult.OverLimit])。仕組みの問題を疑い、人が判断する。
 * - `MISSING`・`STALE` は、signal 表から、そのキーだけの Incremental Snapshot を指示する(今の状態を送り直す)。
 * - `EXTRA` は、レガシーにないことを確かめ直してから、出力に tombstone を書く(Snapshot では削除を作れないため)。
 */
public interface ResyncLegacyOrdersUseCase {
    public suspend operator fun invoke(report: ReconciliationReport): Result<ResyncResult, DomainError>
}

/** 再同期の結果。 */
public sealed interface ResyncResult {
    /** ずれがない(何もしない)。 */
    public data object NothingToDo : ResyncResult

    /** ずれのキーが上限を超えた。何もしない。 */
    public data class OverLimit(
        val driftKeys: Int,
        val limit: Int,
    ) : ResyncResult

    /**
     * 取り直しを指示した。
     *
     * @property snapshotRequested Incremental Snapshot を指示したキー(MISSING・STALE)
     * @property tombstoned tombstone を書いたキー(EXTRA。レガシーにないことを確かめ直したもの)
     * @property reappeared EXTRA だったが、確かめ直したらレガシーにあった(新しく登録された)キー。tombstone は書かず、次の照合で比べる
     */
    public data class Requested(
        val snapshotRequested: Set<String>,
        val tombstoned: Set<String>,
        val reappeared: Set<String>,
    ) : ResyncResult
}
