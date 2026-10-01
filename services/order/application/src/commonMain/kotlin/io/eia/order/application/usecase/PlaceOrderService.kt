package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.PlaceOrderCommand
import io.eia.order.application.port.inbound.PlaceOrderUseCase
import io.eia.order.application.port.outbound.OrderAuditTrail
import io.eia.order.application.port.outbound.OrderIdGenerator
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.order.domain.Order
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import kotlin.time.Clock

/**
 * [PlaceOrderUseCase] の実装。検証(`Order.place`)はトランザクションの外で行い、違反があれば保存しない。
 * 受け付けの時刻は [clock] から取る(テストでは固定の時計を渡す。ADR-0011 §8)。
 * 保存と監査の記録([audit])は同じトランザクションで行う。どちらかが失敗すれば、両方を取り消す(ADR-0017 §4)。
 */
public class PlaceOrderService(
    private val repository: OrderRepository,
    private val transaction: TransactionRunner,
    private val ids: OrderIdGenerator,
    private val clock: Clock,
    private val audit: OrderAuditTrail,
) : PlaceOrderUseCase {
    override suspend fun invoke(command: PlaceOrderCommand): Result<Order, DomainError> =
        Order.place(ids.next(), command.draft, clock.now()).flatMap { order ->
            transaction.inTransaction {
                repository
                    .insert(order)
                    .flatMap { audit.orderPlaced(order, command.requestedBy, command.requestDigest) }
                    .map { order }
            }
        }
}
