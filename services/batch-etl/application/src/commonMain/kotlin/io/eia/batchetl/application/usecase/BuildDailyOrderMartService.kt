package io.eia.batchetl.application.usecase

import io.eia.batchetl.application.port.inbound.BuildDailyOrderMartUseCase
import io.eia.batchetl.application.port.inbound.MartBuildReport
import io.eia.batchetl.application.port.outbound.OrderMart
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.LateDataPolicy
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * [day] と、遅れて届いたデータで変わった過去の日([LateDataPolicy])のパーティションを作り直す。
 * 日ごとに 1 つのトランザクションなので、途中で止まっても、作り直した日はそのまま正しく、残りは再実行で作り直す。
 */
public class BuildDailyOrderMartService(
    private val mart: OrderMart,
) : BuildDailyOrderMartUseCase {
    @Suppress("ReturnCount") // 印を読めない・作り直せないときは、そこで返す
    override suspend fun invoke(day: BusinessDay): Result<MartBuildReport, DomainError> {
        val plan =
            when (val dirty = mart.dirtyDays()) {
                is Result.Err -> return dirty
                is Result.Ok -> LateDataPolicy.plan(day, dirty.value)
            }
        val rebuilt = mutableListOf<Pair<BusinessDay, Int>>()
        for (target in plan.rebuild) {
            when (val count = mart.rebuild(target)) {
                is Result.Err -> return err(count.error)
                is Result.Ok -> rebuilt += target to count.value
            }
        }
        return ok(MartBuildReport(rebuilt, plan.tooOld))
    }
}
