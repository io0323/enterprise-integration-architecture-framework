package io.eia.shipping.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shipping.domain.Shipment

/** 注文ごとの出荷を、まとめて読む(INT-SHIPPING-003。ADR-0042 §4)。読み取りだけで、出荷を変えない。 */
public interface GetShipmentsUseCase {
    /**
     * [orderIds] は 1〜[MAX_ORDER_IDS] 件(重複は 1 件として扱う)で、1 件は 1〜64 文字。違反は `ValidationError`。
     * 記録のない注文は結果に含めない。Saga の内部の印(取消済み)も含めない。
     */
    public suspend operator fun invoke(orderIds: List<String>): Result<List<Shipment>, DomainError>

    public companion object {
        public const val MAX_ORDER_IDS: Int = 20
    }
}
