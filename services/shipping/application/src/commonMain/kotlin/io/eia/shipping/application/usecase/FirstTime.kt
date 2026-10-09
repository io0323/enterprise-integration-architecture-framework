package io.eia.shipping.application.usecase

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.inbound.CommandOutcome
import io.eia.shipping.application.port.outbound.ProcessedCommands

/** 初めての `ce_id` なら [block] を実行して PROCESSED、処理済みなら DUPLICATE(何もしない)。 */
internal suspend fun firstTime(
    processed: ProcessedCommands,
    envelope: CommandEnvelope,
    block: suspend () -> Result<Unit, DomainError>,
): Result<CommandOutcome, DomainError> =
    processed.markProcessed(envelope.messageId, envelope.topic).flatMap { first ->
        if (first) block().map { CommandOutcome.PROCESSED } else ok(CommandOutcome.DUPLICATE)
    }
