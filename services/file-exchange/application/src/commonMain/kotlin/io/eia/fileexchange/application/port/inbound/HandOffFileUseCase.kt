package io.eia.fileexchange.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 受け取った [name] のファイルを、取引先に送るための受け渡しの場所に写す(ADR-0032 §8・ADR-0040 §7。P12 ⑦b)。受け取りの実行の後のステップ
 * (ロックの中)で呼ぶ。quarantine に移したファイルは写さない。取引先への送信は b2b-gateway が行う。
 */
public interface HandOffFileUseCase {
    public suspend operator fun invoke(name: String): Result<HandoffReport, DomainError>
}

/** @property quarantined quarantine に移したファイルなので、写さなかった */
public data class HandoffReport(
    val name: String,
    val quarantined: Boolean,
)

/**
 * 切り替えの 1 回きりの移行(P12 ⑦b。承認の条件 2)。file-exchange が直接送っていた頃に archive に収め、送り終えていないファイルを、
 * 受け渡しの場所に写す(写さないと、切り替えの後は誰も送らない)。[execute] が false なら写さずに対象だけを返す(dry-run)。
 * b2b-gateway は送る前に取引先の側の正式名を確かめるので、送り終えていた取引先に二重には届かない。
 */
public interface HandOffUnsentUseCase {
    public suspend operator fun invoke(
        since: kotlin.time.Instant,
        execute: Boolean,
    ): Result<UnsentReport, DomainError>
}

/** @property candidates 対象のファイル。@property handedOff 写したファイル(dry-run では空)。@property failed 写せなかったファイル */
public data class UnsentReport(
    val candidates: List<String>,
    val handedOff: List<String>,
    val failed: List<Pair<String, DomainError>>,
)
