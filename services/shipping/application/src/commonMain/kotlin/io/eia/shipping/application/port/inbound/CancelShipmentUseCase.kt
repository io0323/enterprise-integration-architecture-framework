package io.eia.shipping.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 出荷の取消(`shipping.shipment.cmd-cancel.v1`。補償)。 */
public interface CancelShipmentUseCase {
    public suspend operator fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError>
}
