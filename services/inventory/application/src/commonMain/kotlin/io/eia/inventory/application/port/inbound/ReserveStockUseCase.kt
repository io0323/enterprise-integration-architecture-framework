package io.eia.inventory.application.port.inbound

import io.eia.inventory.domain.StockLine
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 在庫の引当(`inventory.stock.cmd-reserve.v1`)。 */
public interface ReserveStockUseCase {
    public suspend operator fun invoke(
        envelope: CommandEnvelope,
        lines: List<StockLine>,
    ): Result<CommandOutcome, DomainError>
}
