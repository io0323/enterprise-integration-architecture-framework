package io.eia.batchetl.application.port.inbound

import io.eia.batchetl.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * [day] の日次のパーティションを、取り込んだデータから作る(ELT の変換。パーティションの置換)。
 * 遅れて届いたデータで変わった過去の日も、決めた範囲で作り直す(ADR-0031 §5)。
 */
public interface BuildDailyOrderMartUseCase {
    public suspend operator fun invoke(day: BusinessDay): Result<MartBuildReport, DomainError>
}

/**
 * @property rebuilt 作り直した日と、その日の注文の件数(古い順)
 * @property tooOld 変わったが、古いので自動では作り直さなかった日
 */
public data class MartBuildReport(
    val rebuilt: List<Pair<BusinessDay, Int>>,
    val tooOld: List<BusinessDay>,
)
