package io.eia.bffgraphql.application.port.inbound

import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 注文をまとめて読む。ない注文は結果に含めない。 */
public interface LoadOrdersUseCase {
    public suspend operator fun invoke(ids: Set<String>): Result<Map<String, OrderView>, DomainError>
}

/** 在庫をまとめて読む。件数が下流の上限を超えるときは分けて読む。台帳にない SKU は結果に含めない。 */
public interface LoadStockLevelsUseCase {
    public suspend operator fun invoke(skus: Set<String>): Result<Map<String, StockView>, DomainError>
}

/** 出荷をまとめて読む。出荷の記録がない注文は結果に含めない。 */
public interface LoadShipmentsUseCase {
    public suspend operator fun invoke(orderIds: Set<String>): Result<Map<String, ShipmentView>, DomainError>
}
