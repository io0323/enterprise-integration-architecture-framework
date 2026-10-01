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
 * 受け付けたことは、注文の保存と同じトランザクションで監査に記録する(ADR-0017 §4。[io.eia.order.application.port.outbound.OrderAuditTrail])。
 *
 * @return 受け付けた注文。違反は `ValidationError`(すべての項目)、保存の失敗は [OrderRepository][io.eia.order.application.port.outbound.OrderRepository] のエラー
 */
public interface PlaceOrderUseCase {
    public suspend operator fun invoke(command: PlaceOrderCommand): Result<Order, DomainError>
}

/**
 * 注文の受け付けの入力。
 *
 * @property draft 注文の内容
 * @property requestedBy 呼び出し元(監査の「誰が」。ADR-0017 §1)
 * @property requestDigest 要求の本文を正規化したバイト列の SHA-256(小文字の 16 進 64 文字)。監査に本文の代わりに残す。
 *   正規化は冪等の指紋と同じ(adapters が `platform/api` の `CanonicalBody` で計算する)
 */
public data class PlaceOrderCommand(
    val draft: OrderDraft,
    val requestedBy: RequestedBy,
    val requestDigest: String?,
)

/**
 * 呼び出し元。P05 では client credentials のクライアント(トークンの `azp`)だけ(ADR-0017 §1)。
 * 利用者個人のトークンで呼ばれる経路ができたら、利用者(`sub`)もここに足して記録する。
 */
public data class RequestedBy(
    val clientId: String,
)
