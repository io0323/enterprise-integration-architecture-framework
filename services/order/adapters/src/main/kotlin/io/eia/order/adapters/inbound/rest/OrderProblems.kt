package io.eia.order.adapters.inbound.rest

import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.shared.kernel.DomainError

/**
 * order-service の業務エラーの、Problem Details への写し方(ADR-0022 §2)。ここにないエラーは `Problem.of` の既定で写す。
 * app は `installProblemDetails { mapper = OrderProblems::mapper }` で同じ写し方を使う。
 */
public object OrderProblems {
    /** 業務エラーの写し方。既定で写すエラーは `null`。 */
    public fun mapper(error: DomainError): Problem? =
        when (error) {
            // 楽観的ロックの衝突: 別の更新が先に確定した(docs/architecture/order-state-machine.md)
            is OrderVersionConflict -> Problem(ProblemType.CONFLICT)

            else -> null
        }

    internal fun of(error: DomainError): Problem = mapper(error) ?: Problem.of(error)
}
