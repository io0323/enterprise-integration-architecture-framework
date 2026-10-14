package io.eia.batchetl.application.usecase

import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.port.inbound.ReconcileOrderMartUseCase
import io.eia.batchetl.application.port.inbound.ReconcileReport
import io.eia.batchetl.application.port.outbound.MartSummaries
import io.eia.batchetl.application.port.outbound.OrderMart
import io.eia.batchetl.application.port.outbound.OrderSummarySource
import io.eia.batchetl.application.port.outbound.ReconciliationLog
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DayReconciliation
import io.eia.batchetl.domain.LateDataPolicy
import io.eia.batchetl.domain.ReconcileOutcome
import io.eia.batchetl.domain.ReconcilePolicy
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

/**
 * 週次の Full 照合(ADR-0031 §6)。
 *
 * - 比べる前に、注文のイベントを今の末尾まで取り込む(比べる時点のずれを小さくする)。
 * - 一致しない日は、もう一度取り込んでその日を作り直し、比べ直す(取り込みと問い合わせの間に届いたイベントで、ずれと判定しないため)。
 *   作り直すのは、遅れて届いたデータを自動で作り直す範囲([LateDataPolicy])の日だけ。それより古い日は作り直さずに比べ直す
 *   (締めた後の数字を自動で変えない。ADR-0031 §5)。
 *   比べ直しても一致しなければ DRIFT(自動では直さない。取りこぼしは作り直しでは戻らないため。運用者が Runbook で調べる)。
 * - order-service に問い合わせられない日は UNVERIFIED にして、ほかの日を比べ続ける(Fallback: その日の照合を次の再実行に延ばす)。
 *   最後に結果を記録してから Err(一時的)を返す。
 */
public class ReconcileOrderMartService(
    private val ingest: IngestOrderEventsUseCase,
    private val mart: OrderMart,
    private val martSummaries: MartSummaries,
    private val source: OrderSummarySource,
    private val log: ReconciliationLog,
) : ReconcileOrderMartUseCase {
    @Suppress("ReturnCount") // 取り込み・記録の失敗は、そこで返す
    override suspend fun invoke(runDay: BusinessDay): Result<ReconcileReport, DomainError> {
        ingest().let { if (it is Result.Err) return it }
        val results = mutableListOf<DayReconciliation>()
        var unavailable = 0
        for (day in ReconcilePolicy.days(runDay)) {
            val first =
                when (val compared = compare(day)) {
                    is Result.Err -> return compared
                    is Result.Ok -> compared.value
                }
            val result = if (first.outcome == ReconcileOutcome.DRIFT) recheck(runDay, day) else ok(first)
            when (result) {
                is Result.Err -> return result
                is Result.Ok -> results += result.value
            }
            if (results.last().source == null) unavailable++
        }
        log.record(runDay, results).let { if (it is Result.Err) return it }
        return if (unavailable > 0) {
            err(UnavailableError("order-service に問い合わせられない日がありました($unavailable 日)。再実行で比べ直します"))
        } else {
            ok(ReconcileReport(runDay, results))
        }
    }

    /** 分析の側を読めなければ Err。order-service に問い合わせられなければ source を null にする(UNVERIFIED)。 */
    private suspend fun compare(day: BusinessDay): Result<DayReconciliation, DomainError> =
        martSummaries.summary(day).map { martSummary -> DayReconciliation(day, source.summary(day).getOrNull(), martSummary) }

    private suspend fun recheck(
        runDay: BusinessDay,
        day: BusinessDay,
    ): Result<DayReconciliation, DomainError> {
        val rebuildable = day >= runDay.minusDays(LateDataPolicy.REBUILD_WINDOW_DAYS)
        return ingest()
            .flatMap { if (rebuildable) mart.rebuild(day).map { } else ok(Unit) }
            .flatMap { compare(day) }
    }
}
