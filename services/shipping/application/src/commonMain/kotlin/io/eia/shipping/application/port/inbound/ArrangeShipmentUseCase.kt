package io.eia.shipping.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.ShipmentLine

/** 出荷の手配(`shipping.shipment.cmd-arrange.v1`)。 */
public interface ArrangeShipmentUseCase {
    public suspend operator fun invoke(
        envelope: CommandEnvelope,
        destination: Destination,
        lines: List<ShipmentLine>,
    ): Result<CommandOutcome, DomainError>
}
