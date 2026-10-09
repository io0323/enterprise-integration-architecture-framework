package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.TimeoutSagasUseCase
import io.eia.order.application.port.outbound.SagaStore
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.order.domain.SagaSignal
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok

/**
 * 期限切れの Saga を進める(ADR-0029 §3・§6)。前進の段は補償へ、補償の段は同じコマンドの送り直し。
 * 1 回のトランザクションで最大 [batchSize] 件をロックし(`FOR UPDATE SKIP LOCKED`)、処理して確定する。期限の判定は DB の時計で、アプリの時計は使わない。
 */
public class TimeoutSagasService(
    private val transactions: TransactionRunner,
    private val sagas: SagaStore,
    private val coordinator: SagaCoordinator,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : TimeoutSagasUseCase {
    init {
        require(batchSize >= 1) { "batchSize は 1 以上にしてください: $batchSize" }
    }

    override suspend fun invoke(): Result<Int, DomainError> {
        var total = 0
        while (true) {
            val batch =
                transactions.inTransaction {
                    when (val expired = sagas.lockExpired(batchSize)) {
                        is Result.Err -> {
                            expired
                        }

                        is Result.Ok -> {
                            var failure: Result.Err<DomainError>? = null
                            for (saga in expired.value) {
                                val handled = coordinator.handle(saga, SagaSignal.STEP_TIMED_OUT)
                                if (handled is Result.Err) {
                                    failure = handled
                                    break
                                }
                            }
                            failure ?: ok(expired.value.size)
                        }
                    }
                }
            when (batch) {
                is Result.Err -> {
                    return batch
                }

                is Result.Ok -> {
                    total += batch.value
                    if (batch.value < batchSize) return ok(total)
                }
            }
        }
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 50
    }
}
