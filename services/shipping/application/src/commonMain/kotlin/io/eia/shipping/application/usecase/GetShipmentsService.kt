package io.eia.shipping.application.usecase

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shipping.application.port.inbound.GetShipmentsUseCase
import io.eia.shipping.application.port.outbound.ShipmentQueries
import io.eia.shipping.domain.MAX_IDENTIFIER_LENGTH
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentStatus

/** 出荷の照会。入力を検査し、[ShipmentQueries] で読み、Saga の内部の印を除く(ADR-0042 §4)。 */
public class GetShipmentsService(
    private val queries: ShipmentQueries,
) : GetShipmentsUseCase {
    override suspend fun invoke(orderIds: List<String>): Result<List<Shipment>, DomainError> {
        val unique = orderIds.toSet()
        val reason =
            when {
                unique.isEmpty() -> "1 件以上にしてください"
                unique.size > GetShipmentsUseCase.MAX_ORDER_IDS -> "${GetShipmentsUseCase.MAX_ORDER_IDS} 件以下にしてください"
                unique.any { it.isEmpty() || it.length > MAX_IDENTIFIER_LENGTH } -> "1 件は 1〜$MAX_IDENTIFIER_LENGTH 文字にしてください"
                else -> null
            }
        if (reason != null) return err(ValidationError.of("orderIds", reason))
        return queries.find(unique).map { shipments ->
            shipments.filter { it.status != ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT }
        }
    }
}
