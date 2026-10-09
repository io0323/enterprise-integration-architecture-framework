package io.eia.shipping.application.usecase

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.inbound.CancelShipmentUseCase
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.inbound.CommandOutcome
import io.eia.shipping.application.port.outbound.ProcessedCommands
import io.eia.shipping.application.port.outbound.ShipmentStore
import io.eia.shipping.application.port.outbound.ShippingReplies
import io.eia.shipping.application.port.outbound.TransactionRunner
import io.eia.shipping.domain.CancelDecision
import io.eia.shipping.domain.ShippingRules
import io.eia.shipping.domain.validateIdentifiers

/**
 * 出荷の取消(補償。ADR-0029 §3・§5)。手配がなければ「取消済み」の印を作り、後から届いた手配の指示を拒否できるようにする。
 * 出荷済みなら取り消さず `ALREADY_SHIPPED` を返す(Saga は補償をやめて完了に進む)。
 */
public class CancelShipmentService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedCommands,
    private val shipments: ShipmentStore,
    private val replies: ShippingReplies,
    private val rules: ShippingRules,
) : CancelShipmentUseCase {
    override suspend fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError> =
        validateIdentifiers("sagaId" to envelope.sagaId, "orderId" to envelope.orderId).flatMap {
            transactions.inTransaction {
                firstTime(processed, envelope) {
                    shipments.findForUpdate(envelope.sagaId).flatMap { existing ->
                        val decision = rules.cancel(existing, envelope.sagaId, envelope.orderId)
                        when (decision) {
                            is CancelDecision.MarkCancelledBeforeArrangement -> shipments.insert(decision.marker)
                            is CancelDecision.Replay -> ok(Unit)
                        }.flatMap { replies.cancelled(envelope.sagaId, envelope.orderId, decision.outcome) }
                    }
                }
            }
        }
}
