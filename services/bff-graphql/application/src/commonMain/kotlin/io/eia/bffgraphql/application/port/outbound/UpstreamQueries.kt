package io.eia.bffgraphql.application.port.outbound

import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 集約の下流。どれもまとめて読む(N+1 にしない。ADR-0043 §5)。「ない」は結果に含めず、「読めなかった」は `Err` で返す。
 * Timeout・Retry・Circuit Breaker は adapters が持つ。Fallback は BFF の部分応答(ADR-0043 §6)。
 */
public interface OrderQueries {
    /** [ids] は 1〜20 件(GET /v1/orders?ids= の上限。SDL の @maxItems で先に確かめている)。 */
    public suspend fun find(ids: Set<String>): Result<List<OrderView>, DomainError>
}

public interface StockQueries {
    /** [skus] は 1〜[MAX_SKUS] 件(BatchGetStock の上限)。 */
    public suspend fun find(skus: Set<String>): Result<List<StockView>, DomainError>

    public companion object {
        public const val MAX_SKUS: Int = 500
    }
}

public interface ShipmentQueries {
    /** [orderIds] は 1〜20 件(BatchGetShipments の上限)。 */
    public suspend fun find(orderIds: Set<String>): Result<List<ShipmentView>, DomainError>
}
