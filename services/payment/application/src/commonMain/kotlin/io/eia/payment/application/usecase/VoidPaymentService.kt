package io.eia.payment.application.usecase

import io.eia.payment.application.port.inbound.CommandEnvelope
import io.eia.payment.application.port.inbound.CommandOutcome
import io.eia.payment.application.port.inbound.VoidPaymentUseCase
import io.eia.payment.application.port.outbound.AuthorizationStore
import io.eia.payment.application.port.outbound.PaymentReplies
import io.eia.payment.application.port.outbound.ProcessedCommands
import io.eia.payment.application.port.outbound.TransactionRunner
import io.eia.payment.domain.PaymentRules
import io.eia.payment.domain.VoidDecision
import io.eia.payment.domain.validateIdentifiers
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok

/**
 * 決済の承認の取消(補償。ADR-0029 §5)。承認がなければ「取消済み」の印を作り、後から届いた承認の指示を拒否できるようにする。
 * 何度届いても 1 回だけ取り消す。
 */
public class VoidPaymentService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedCommands,
    private val authorizations: AuthorizationStore,
    private val replies: PaymentReplies,
    private val rules: PaymentRules,
) : VoidPaymentUseCase {
    override suspend fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError> =
        validateIdentifiers("sagaId" to envelope.sagaId, "orderId" to envelope.orderId).flatMap {
            transactions.inTransaction {
                firstTime(processed, envelope) {
                    authorizations.findForUpdate(envelope.sagaId).flatMap { existing ->
                        val decision = rules.void(existing, envelope.sagaId, envelope.orderId)
                        when (decision) {
                            VoidDecision.Void -> authorizations.markVoided(envelope.sagaId)
                            is VoidDecision.MarkVoidedBeforeAuthorization -> authorizations.insert(decision.marker)
                            is VoidDecision.Replay -> ok(Unit)
                        }.flatMap { replies.voided(envelope.sagaId, envelope.orderId, decision.outcome) }
                    }
                }
            }
        }
}
