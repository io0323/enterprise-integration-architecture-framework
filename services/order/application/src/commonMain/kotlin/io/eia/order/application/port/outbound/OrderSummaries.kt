package io.eia.order.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/** 期間に受け付けた注文の集計(読み取りだけ)。adapters が DB から作る。 */
public interface OrderSummaries {
    /**
     * `orderedAt` が [from] 以上 [until] 未満の注文の件数と、内容のハッシュ(`DailyOrderDigest` の行を注文 ID のバイト順に並べた SHA-256)。
     */
    public suspend fun count(
        from: Instant,
        until: Instant,
    ): Result<OrderCount, DomainError>
}

/** 期間の注文の件数とハッシュ。 */
public data class OrderCount(
    val orders: Long,
    val digest: String,
)
