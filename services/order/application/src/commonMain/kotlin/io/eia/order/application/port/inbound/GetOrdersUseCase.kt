package io.eia.order.application.port.inbound

import io.eia.order.domain.Order
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 注文をまとめて取得する(`GET /v1/orders?ids=`。P13 ⑤)。 */
public interface GetOrdersUseCase {
    /**
     * [ids] は 1〜[MAX_IDS] 件(重複は 1 件として扱う)。違反は `ValidationError`。
     * ない注文と、形式が不正な ID(存在しえない)は [OrderBatch.notFound] に返す(ほかの注文は返す)。
     */
    public suspend operator fun invoke(ids: List<String>): Result<OrderBatch, DomainError>

    public companion object {
        /** 契約の `ids` の `maxItems`。BFF の上限はこれ以下にする(ADR-0043)。 */
        public const val MAX_IDS: Int = 20
    }
}

/**
 * @property orders 見つかった注文。指定した順
 * @property notFound 見つからなかった ID(読めなかったこととは区別する)
 */
public data class OrderBatch(
    public val orders: List<Order>,
    public val notFound: List<String>,
)
