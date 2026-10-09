package io.eia.payment.application.usecase

import io.eia.payment.application.port.inbound.AuthorizePaymentUseCase
import io.eia.payment.application.port.inbound.CommandEnvelope
import io.eia.payment.application.port.inbound.CommandOutcome
import io.eia.payment.application.port.outbound.AuthorizationIds
import io.eia.payment.application.port.outbound.AuthorizationStore
import io.eia.payment.application.port.outbound.PaymentReplies
import io.eia.payment.application.port.outbound.ProcessedCommands
import io.eia.payment.application.port.outbound.TransactionRunner
import io.eia.payment.domain.Amount
import io.eia.payment.domain.AuthorizeDecision
import io.eia.payment.domain.PaymentRules
import io.eia.payment.domain.validateIdentifiers
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok

/**
 * 決済の承認(模擬。ADR-0029 §5・§7)。1 つのトランザクションで、冪等消費の記録 → 承認の記録のロック → 判定 → 書き込み → 返事(Outbox)。
 * 同じ `ce_id` は何もしない。同じ Saga ID は記録から返事を返し直す。
 */
public class AuthorizePaymentService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedCommands,
    private val authorizations: AuthorizationStore,
    private val replies: PaymentReplies,
    private val rules: PaymentRules,
    private val ids: AuthorizationIds,
) : AuthorizePaymentUseCase {
    override suspend fun invoke(
        envelope: CommandEnvelope,
        customerId: String,
        amount: Amount,
    ): Result<CommandOutcome, DomainError> =
        validateIdentifiers("sagaId" to envelope.sagaId, "orderId" to envelope.orderId, "customerId" to customerId).flatMap {
            transactions.inTransaction {
                firstTime(processed, envelope) {
                    authorizations.findForUpdate(envelope.sagaId).flatMap { existing ->
                        val decision = rules.authorize(existing, envelope.sagaId, envelope.orderId, amount, ids::next)
                        when (decision) {
                            is AuthorizeDecision.Record -> authorizations.insert(decision.authorization)
                            is AuthorizeDecision.Replay -> ok(Unit)
                        }.flatMap { replies.authorizeReplied(envelope.sagaId, envelope.orderId, decision.reply) }
                    }
                }
            }
        }
}
