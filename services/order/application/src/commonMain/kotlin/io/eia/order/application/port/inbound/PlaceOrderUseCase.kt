package io.eia.order.application.port.inbound

import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 注文を受け付ける(`POST /v1/orders`)。
 *
 * 入力の [OrderDraft] は、adapters が契約の DTO から作る(金額の文字列の解析と通貨の解決は adapters)。
 * `Idempotency-Key` の扱いは adapters(`platform/api` の `respondIdempotently`)が、このユースケースを包んで行う(ADR-0022 §3)。
 *
 * @return 受け付けた注文。違反は `ValidationError`(すべての項目)、保存の失敗は [OrderRepository][io.eia.order.application.port.outbound.OrderRepository] のエラー
 */
public interface PlaceOrderUseCase {
    public suspend operator fun invoke(draft: OrderDraft): Result<Order, DomainError>
}
