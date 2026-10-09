package io.eia.payment.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 決済の承認の取消(`payment.payment.cmd-void.v1`。補償)。 */
public interface VoidPaymentUseCase {
    public suspend operator fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError>
}
