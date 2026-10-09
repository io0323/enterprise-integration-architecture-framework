package io.eia.inventory.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 在庫の解放(`inventory.stock.cmd-release.v1`。補償)。 */
public interface ReleaseStockUseCase {
    public suspend operator fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError>
}
