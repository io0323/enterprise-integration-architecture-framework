package io.eia.order.application.port.outbound

import io.eia.order.domain.OrderId

/**
 * 注文 ID の採番。既定の実装(採番の方式)は adapters で決める(P05 ④a。#8 のチェックリスト)。
 * 返す ID は一意で、`OrderId` の規則(64 文字以内)を満たす。
 */
public fun interface OrderIdGenerator {
    public fun next(): OrderId
}
