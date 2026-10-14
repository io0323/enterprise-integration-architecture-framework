package io.eia.batchetl.application.port.inbound

import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DayReconciliation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 週次の Full 照合(Framework 7.3: 件数とハッシュの突合で、差分の取り込みのずれを検知する。ADR-0031 §6)。
 * [runDay] の前日から決めた日数前までの各日について、order-service の日次の集計と分析のパーティションを比べ、結果を記録する。
 *
 * order-service に問い合わせられない日があれば、比べた日の結果を記録したうえで Err(一時的)を返す(次の再実行で比べ直す)。
 */
public interface ReconcileOrderMartUseCase {
    public suspend operator fun invoke(runDay: BusinessDay): Result<ReconcileReport, DomainError>
}

/** @property days 日ごとの結果(古い順) */
public data class ReconcileReport(
    val runDay: BusinessDay,
    val days: List<DayReconciliation>,
)
