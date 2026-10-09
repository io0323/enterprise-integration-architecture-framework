package io.eia.inventory.application.usecase

import io.eia.inventory.application.port.inbound.PurgeExpiredRecordsUseCase
import io.eia.inventory.application.port.inbound.PurgedRecords
import io.eia.inventory.application.port.outbound.ProcessedCommands
import io.eia.inventory.application.port.outbound.ReservationStore
import io.eia.inventory.domain.SettledRetention
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

/**
 * 保持期間を過ぎた記録を消す。終わった引当の記録(印を含む)は [retention](既定 30 日。ADR-0029 §5)、
 * 冪等消費の記録は platform/inbox の保持期間(14 日。ADR-0028 §3)。どちらも DB の時計で判定する。
 */
public class PurgeExpiredRecordsService(
    private val reservations: ReservationStore,
    private val processed: ProcessedCommands,
    private val retention: SettledRetention = SettledRetention.DEFAULT,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : PurgeExpiredRecordsUseCase {
    init {
        require(batchSize >= 1) { "batchSize は 1 以上にしてください: $batchSize" }
    }

    override suspend fun invoke(): Result<PurgedRecords, DomainError> =
        drain { reservations.purgeSettled(retention.value, batchSize) }.flatMap { purgedReservations ->
            drain { processed.purgeExpired(batchSize) }.map { PurgedRecords(purgedReservations, it) }
        }

    /** 上限の件数より少なく消えるまで繰り返す(長いロックを避ける)。 */
    private suspend fun drain(batch: suspend () -> Result<Int, DomainError>): Result<Int, DomainError> {
        var total = 0
        while (true) {
            when (val deleted = batch()) {
                is Result.Err -> {
                    return deleted
                }

                is Result.Ok -> {
                    total += deleted.value
                    if (deleted.value < batchSize) return ok(total)
                }
            }
        }
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 1000
    }
}
