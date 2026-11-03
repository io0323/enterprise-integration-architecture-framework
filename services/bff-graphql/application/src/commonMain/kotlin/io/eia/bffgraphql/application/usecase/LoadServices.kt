package io.eia.bffgraphql.application.usecase

import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.bffgraphql.application.port.inbound.LoadOrdersUseCase
import io.eia.bffgraphql.application.port.inbound.LoadShipmentsUseCase
import io.eia.bffgraphql.application.port.inbound.LoadStockLevelsUseCase
import io.eia.bffgraphql.application.port.outbound.OrderQueries
import io.eia.bffgraphql.application.port.outbound.ShipmentQueries
import io.eia.bffgraphql.application.port.outbound.StockQueries
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

public class LoadOrdersService(
    private val queries: OrderQueries,
) : LoadOrdersUseCase {
    override suspend fun invoke(ids: Set<String>): Result<Map<String, OrderView>, DomainError> =
        queries.find(ids).map { orders -> orders.associateBy { it.id } }
}

public class LoadShipmentsService(
    private val queries: ShipmentQueries,
) : LoadShipmentsUseCase {
    override suspend fun invoke(orderIds: Set<String>): Result<Map<String, ShipmentView>, DomainError> =
        queries.find(orderIds).map { shipments -> shipments.associateBy { it.orderId } }
}

/** 明細の数に契約の上限がないので、1 回の上限([StockQueries.MAX_SKUS])ごとに分けて読む。1 つでも読めなければ失敗にする。 */
public class LoadStockLevelsService(
    private val queries: StockQueries,
    private val chunkSize: Int = StockQueries.MAX_SKUS,
) : LoadStockLevelsUseCase {
    override suspend fun invoke(skus: Set<String>): Result<Map<String, StockView>, DomainError> {
        val levels = mutableMapOf<String, StockView>()
        skus.chunked(chunkSize).forEach { chunk ->
            when (val found = queries.find(chunk.toSet())) {
                is Result.Ok -> found.value.forEach { levels[it.sku] = it }
                is Result.Err -> return found
            }
        }
        return ok(levels)
    }
}
