package io.eia.payment.application.port.inbound

import io.eia.payment.domain.Amount
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 決済の承認(`payment.payment.cmd-authorize.v1`)。 */
public interface AuthorizePaymentUseCase {
    public suspend operator fun invoke(
        envelope: CommandEnvelope,
        customerId: String,
        amount: Amount,
    ): Result<CommandOutcome, DomainError>
}
