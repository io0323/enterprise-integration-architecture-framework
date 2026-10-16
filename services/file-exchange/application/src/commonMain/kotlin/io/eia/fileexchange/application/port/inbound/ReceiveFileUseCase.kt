package io.eia.fileexchange.application.port.inbound

import io.eia.fileexchange.domain.ReceivedFile
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 受け口に届いた [name] のファイルを受け取る(ADR-0032 §5): 記録がなければ検査し、移動の決定を記録してから移す。
 * 記録が「移動中」なら、検査をせずに記録した移動を続ける(移動の途中で落ちた後の再開)。ファイルの到着の実行(ロックの中)で呼ぶ。
 */
public interface ReceiveFileUseCase {
    public suspend operator fun invoke(name: String): Result<ReceiveOutcome, DomainError>
}

/**
 * @property resumed 前の実行の移動の続きをした
 * @property alreadyStored 移し終えた記録がすでにあり、何もしなかった
 */
public data class ReceiveOutcome(
    val file: ReceivedFile,
    val resumed: Boolean,
    val alreadyStored: Boolean,
)
