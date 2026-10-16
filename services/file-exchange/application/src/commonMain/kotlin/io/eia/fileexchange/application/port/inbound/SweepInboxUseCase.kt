package io.eia.fileexchange.application.port.inbound

import io.eia.fileexchange.domain.ExtraArrival
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 受け口を見回る(ADR-0032 §5)。受け取り済み(移し終えた)の名前で届いたファイルを、送り直し(`duplicate`)か
 * 名前の使い回し(`name_reused`)として quarantine に移す。まだ受け取っていない名前のファイルには触れない(ファイルの到着の実行が扱う)。
 */
public interface SweepInboxUseCase {
    public suspend operator fun invoke(): Result<SweepReport, DomainError>
}

/**
 * @property moved quarantine に移した、受け取り済みの名前のファイル(処理中のまま残っていたものの再開を含む)
 * @property waiting 受け口に残っているファイルの数
 * @property oldestCreatedAt 受け口に残っているファイルのマニフェストの createdAt のうち最も古いもの(滞留の目安)
 */
public data class SweepReport(
    val moved: List<ExtraArrival>,
    val waiting: Int,
    val oldestCreatedAt: Instant?,
)
