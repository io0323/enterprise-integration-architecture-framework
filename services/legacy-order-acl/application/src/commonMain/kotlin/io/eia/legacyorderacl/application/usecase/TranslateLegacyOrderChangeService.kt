package io.eia.legacyorderacl.application.usecase

import io.eia.legacyorderacl.application.port.inbound.LegacyOrderChange
import io.eia.legacyorderacl.application.port.inbound.TranslateLegacyOrderChangeUseCase
import io.eia.legacyorderacl.application.port.inbound.TranslationOutcome
import io.eia.legacyorderacl.application.port.outbound.LegacyOrderStatePublisher
import io.eia.legacyorderacl.domain.LegacyOrderTranslation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/** [TranslateLegacyOrderChangeUseCase] の実装。状態を持たない(1 つの変更から 1 つの発行。ADR-0026 §6)。 */
public class TranslateLegacyOrderChangeService(
    private val publisher: LegacyOrderStatePublisher,
) : TranslateLegacyOrderChangeUseCase {
    override suspend fun invoke(change: LegacyOrderChange): Result<TranslationOutcome, DomainError> =
        when (change) {
            is LegacyOrderChange.Upsert -> {
                LegacyOrderTranslation
                    .translate(change.row)
                    .mapError<_, _, DomainError> { it }
                    .flatMap { order -> publisher.upsert(order, change.position) }
                    .map { TranslationOutcome.UPSERTED }
            }

            is LegacyOrderChange.Delete -> {
                LegacyOrderTranslation
                    .orderNumber(change.orderNumber)
                    .mapError<_, _, DomainError> { it }
                    .flatMap { number -> publisher.delete(number, change.position) }
                    .map { TranslationOutcome.DELETED }
            }
        }
}
