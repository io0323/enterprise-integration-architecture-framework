package io.eia.order.application.port.outbound

import io.eia.order.application.port.inbound.RequestedBy
import io.eia.order.domain.Order
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 注文の監査の記録(ADR-0017)。adapters が `platform/audit` で実装する(application は監査の部品に依存しない)。
 *
 * 業務の更新と**同じトランザクションの中**で呼ぶ。記録に失敗すれば、業務の更新も取り消す(記録のない業務の更新を残さない)。
 * 業務の更新が取り消されれば、記録も残らない。
 */
public interface OrderAuditTrail {
    /** 注文を受け付けたこと(`order.create`)を記録する。[requestDigest] は要求の本文の SHA-256(本文そのものは記録しない)。 */
    public suspend fun orderPlaced(
        order: Order,
        requestedBy: RequestedBy,
        requestDigest: String?,
    ): Result<Unit, DomainError>
}
