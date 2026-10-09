package io.eia.shipping.application.usecase

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.inbound.ArrangeShipmentUseCase
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.inbound.CommandOutcome
import io.eia.shipping.application.port.outbound.ProcessedCommands
import io.eia.shipping.application.port.outbound.ShipmentStamps
import io.eia.shipping.application.port.outbound.ShipmentStore
import io.eia.shipping.application.port.outbound.ShippingReplies
import io.eia.shipping.application.port.outbound.TransactionRunner
import io.eia.shipping.domain.ArrangeDecision
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.ShipmentLine
import io.eia.shipping.domain.ShippingRules
import io.eia.shipping.domain.validateIdentifiers

/**
 * 出荷の手配(模擬。ADR-0029 §5・§7)。1 つのトランザクションで、冪等消費の記録 → 出荷の記録のロック → 判定 → 書き込み → 返事(Outbox)。
 * 同じ `ce_id` は何もしない。同じ Saga ID は記録から返事を返し直す。
 */
public class ArrangeShipmentService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedCommands,
    private val shipments: ShipmentStore,
    private val replies: ShippingReplies,
    private val rules: ShippingRules,
    private val stamps: ShipmentStamps,
) : ArrangeShipmentUseCase {
    override suspend fun invoke(
        envelope: CommandEnvelope,
        destination: Destination,
        lines: List<ShipmentLine>,
    ): Result<CommandOutcome, DomainError> =
        validateIdentifiers("sagaId" to envelope.sagaId, "orderId" to envelope.orderId)
            .flatMap { if (lines.isEmpty()) err(ValidationError(listOf(FieldViolation("lines", "1 件以上にしてください")))) else ok(Unit) }
            .flatMap {
                transactions.inTransaction {
                    firstTime(processed, envelope) {
                        shipments.findForUpdate(envelope.sagaId).flatMap { existing ->
                            val decision = rules.arrange(existing, envelope.sagaId, envelope.orderId, destination, stamps::next)
                            when (decision) {
                                is ArrangeDecision.Record -> shipments.insert(decision.shipment)
                                is ArrangeDecision.Replay -> ok(Unit)
                            }.flatMap { replies.arrangeReplied(envelope.sagaId, envelope.orderId, decision.reply) }
                        }
                    }
                }
            }
}
