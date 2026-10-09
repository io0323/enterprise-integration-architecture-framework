package io.eia.inventory.application.usecase

import io.eia.inventory.application.port.inbound.CommandEnvelope
import io.eia.inventory.application.port.inbound.CommandOutcome
import io.eia.inventory.application.port.inbound.ReserveStockUseCase
import io.eia.inventory.application.port.outbound.InventoryReplies
import io.eia.inventory.application.port.outbound.ProcessedCommands
import io.eia.inventory.application.port.outbound.ReservationStore
import io.eia.inventory.application.port.outbound.StockLedger
import io.eia.inventory.application.port.outbound.TransactionRunner
import io.eia.inventory.domain.InventoryRules
import io.eia.inventory.domain.ReserveDecision
import io.eia.inventory.domain.StockLine
import io.eia.inventory.domain.validateIds
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

/**
 * 在庫の引当(ADR-0029 §5・§7)。1 つのトランザクションで、冪等消費の記録 → 引当の記録のロック → 在庫のロック → 判定 → 書き込み → 返事(Outbox)。
 * 同じ `ce_id` は何もしない。同じ Saga ID は記録から返事を返し直す(在庫は変えない)。
 */
public class ReserveStockService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedCommands,
    private val reservations: ReservationStore,
    private val stock: StockLedger,
    private val replies: InventoryReplies,
) : ReserveStockUseCase {
    override suspend fun invoke(
        envelope: CommandEnvelope,
        lines: List<StockLine>,
    ): Result<CommandOutcome, DomainError> =
        validateIds(envelope.sagaId, envelope.orderId).flatMap {
            transactions.inTransaction {
                firstTime(processed, envelope) {
                    reservations.findForUpdate(envelope.sagaId).flatMap { existing ->
                        // 同じ Saga の記録があれば在庫を読まない(ロックも取らない)
                        val skus = if (existing == null) lines.map { it.sku }.toSet() else emptySet()
                        stock.lock(skus).flatMap { levels ->
                            val decision = InventoryRules.reserve(existing, envelope.sagaId, envelope.orderId, lines, levels)
                            when (decision) {
                                is ReserveDecision.Reserve -> {
                                    reservations.insert(decision.reservation).flatMap { stock.adjustReserved(decision.increments) }
                                }

                                is ReserveDecision.Reject -> {
                                    reservations.insert(decision.reservation)
                                }

                                is ReserveDecision.Replay -> {
                                    ok(Unit)
                                }
                            }.flatMap { replies.reserveReplied(envelope.sagaId, envelope.orderId, decision.reply) }
                        }
                    }
                }
            }
        }
}
