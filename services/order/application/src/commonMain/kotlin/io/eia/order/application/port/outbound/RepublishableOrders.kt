package io.eia.order.application.port.outbound

import io.eia.order.domain.Order
import io.eia.order.domain.SagaFailure
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/** 再発行の対象を読む(ADR-0046)。 */
public interface RepublishableOrders {
    /** 注文の時刻が [from] 以上 [to] 未満の注文を、ID の順に [afterId] の次から最大 [limit] 件読む。 */
    public suspend fun page(
        from: Instant,
        to: Instant,
        afterId: String?,
        limit: Int,
    ): Result<List<RepublishableOrder>, DomainError>
}

/**
 * @property order 今の注文
 * @property failure Saga の補償の理由(取消の理由。なければ null)
 * @property updatedAt 注文の最後の更新の時刻(今の状態への遷移の時刻として使う)
 */
public data class RepublishableOrder(
    val order: Order,
    val failure: SagaFailure?,
    val updatedAt: Instant,
)

/** 再発行の目印(イベントのヘッダ `eiaf.republished`・`eiaf.republish.reason`・`eiaf.republish.run-id`)。 */
public data class RepublishMark(
    val reason: String,
    val runId: String,
)
