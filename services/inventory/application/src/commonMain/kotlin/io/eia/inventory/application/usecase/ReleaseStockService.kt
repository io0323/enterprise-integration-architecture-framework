package io.eia.inventory.application.usecase

import io.eia.inventory.application.port.inbound.CommandEnvelope
import io.eia.inventory.application.port.inbound.CommandOutcome
import io.eia.inventory.application.port.inbound.ReleaseStockUseCase
import io.eia.inventory.application.port.outbound.InventoryReplies
import io.eia.inventory.application.port.outbound.ProcessedCommands
import io.eia.inventory.application.port.outbound.ReservationStore
import io.eia.inventory.application.port.outbound.StockLedger
import io.eia.inventory.application.port.outbound.TransactionRunner
import io.eia.inventory.domain.InventoryRules
import io.eia.inventory.domain.ReleaseDecision
import io.eia.inventory.domain.validateIds
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok

/**
 * 在庫の解放(補償。ADR-0029 §5)。引当がなければ「取消済み」の印を作り、後から届いた引当の指示を拒否できるようにする。
 * 何度届いても在庫は 1 回だけ戻る。
 */
public class ReleaseStockService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedCommands,
    private val reservations: ReservationStore,
    private val stock: StockLedger,
    private val replies: InventoryReplies,
) : ReleaseStockUseCase {
    override suspend fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError> =
        validateIds(envelope.sagaId, envelope.orderId).flatMap {
            transactions.inTransaction {
                firstTime(processed, envelope) {
                    reservations.findForUpdate(envelope.sagaId).flatMap { existing ->
                        val decision = InventoryRules.release(existing, envelope.sagaId, envelope.orderId)
                        when (decision) {
                            is ReleaseDecision.Release -> {
                                // 在庫をロックしてから戻す(引当と同じく SKU の順)
                                stock
                                    .lock(decision.decrements.keys)
                                    .flatMap { reservations.markReleased(envelope.sagaId) }
                                    .flatMap { stock.adjustReserved(decision.decrements.mapValues { (_, quantity) -> -quantity }) }
                            }

                            is ReleaseDecision.MarkReleasedBeforeReservation -> {
                                reservations.insert(decision.marker)
                            }

                            is ReleaseDecision.Replay -> {
                                ok(Unit)
                            }
                        }.flatMap { replies.released(envelope.sagaId, envelope.orderId, decision.outcome) }
                    }
                }
            }
        }
}
