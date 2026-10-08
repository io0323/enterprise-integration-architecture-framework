package io.eia.legacyorderacl.application.port.outbound

import io.eia.legacyorderacl.application.port.inbound.ChangePosition
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 注文のヘッダの最新の状態の発行先(整形済みのトピック `sales.legacy-order.changed.v1`。compacted。ADR-0026 §9)。
 * 注文番号をキーにし、同じ注文の発行は呼んだ順に届くこと。戻ったときは、発行が確定している(At-Least-Once。ADR-0026 §6)。
 */
public interface LegacyOrderStatePublisher {
    /** 最新の状態を発行する(Upsert)。 */
    public suspend fun upsert(
        order: LegacyOrder,
        position: ChangePosition,
    ): Result<Unit, DomainError>

    /** 削除を発行する(値のない tombstone)。 */
    public suspend fun delete(
        orderNumber: String,
        position: ChangePosition,
    ): Result<Unit, DomainError>
}
