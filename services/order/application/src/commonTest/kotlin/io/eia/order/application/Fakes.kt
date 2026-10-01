package io.eia.order.application

import io.eia.order.application.port.inbound.RequestedBy
import io.eia.order.application.port.outbound.OrderAuditTrail
import io.eia.order.application.port.outbound.OrderIdGenerator
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.order.domain.RestoredLine
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * メモリ上の保存先。[OrderRepository] の約束(ID の重複は ConflictError、楽観的ロック)どおりに振る舞う。
 * [transaction] の中の書き込みは、確定したときだけ反映する。[failWith] を設定すると、次の操作をそのエラーで失敗させる。
 */
internal class FakeOrderRepository(
    private val transaction: FakeTransactionRunner,
) : OrderRepository {
    val orders = mutableMapOf<OrderId, Order>()
    var failWith: DomainError? = null

    override suspend fun insert(order: Order): Result<Unit, DomainError> {
        val failure = failWith
        return when {
            failure != null -> {
                err(failure)
            }

            order.id in orders -> {
                err(ConflictError("注文 ${order.id} は登録済みです"))
            }

            else -> {
                transaction.write { orders[order.id] = order }
                ok(Unit)
            }
        }
    }

    override suspend fun findById(id: OrderId): Result<Order?, DomainError> = failWith?.let { err(it) } ?: ok(orders[id])

    override suspend fun update(order: Order): Result<Order, DomainError> {
        val stored = orders[order.id]
        val failure = failWith
        return when {
            failure != null -> {
                err(failure)
            }

            stored == null -> {
                err(ConflictError("注文 ${order.id} はありません"))
            }

            stored.version != order.version -> {
                err(OrderVersionConflict(order.id, order.version))
            }

            else -> {
                val next = order.withVersion(order.version + 1)
                transaction.write { orders[order.id] = next }
                ok(next)
            }
        }
    }
}

internal fun Order.withVersion(version: Long): Order =
    Order.restore(
        id,
        customerId,
        status,
        orderedAt,
        lines.map { RestoredLine(it.lineNumber, it.productId, it.sku, it.quantity, it.unitPrice, it.lineAmount) },
        totalAmount,
        shippingAddress,
        version,
    )

/** 書き込みを溜め、`Ok` なら反映して確定、`Err` か例外なら捨てて取り消す。確定と取り消しの回数を数える。 */
internal class FakeTransactionRunner : TransactionRunner {
    var commits = 0
    var rollbacks = 0
    private var pending: MutableList<() -> Unit>? = null

    override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        val writes = mutableListOf<() -> Unit>()
        pending = writes
        try {
            val result = block()
            if (result is Result.Ok) {
                writes.forEach { it() }
                commits++
            } else {
                rollbacks++
            }
            return result
        } catch (e: Throwable) {
            rollbacks++
            throw e
        } finally {
            pending = null
        }
    }

    fun write(action: () -> Unit) {
        pending?.add(action) ?: action()
    }
}

/** `ord-1`, `ord-2`, ... を順に返す。 */
internal class SequentialIds : OrderIdGenerator {
    var issued = 0

    override fun next(): OrderId = (OrderId.parse("ord-${++issued}") as Result.Ok).value
}

/** メモリ上の監査の記録。[transaction] が確定したときだけ残す。[failWith] を設定すると、記録を失敗させる。 */
internal class FakeOrderAuditTrail(
    private val transaction: FakeTransactionRunner,
) : OrderAuditTrail {
    data class Entry(
        val orderId: OrderId,
        val requestedBy: RequestedBy,
        val requestDigest: String?,
    )

    val entries = mutableListOf<Entry>()
    var failWith: DomainError? = null

    override suspend fun orderPlaced(
        order: Order,
        requestedBy: RequestedBy,
        requestDigest: String?,
    ): Result<Unit, DomainError> {
        failWith?.let { return err(it) }
        transaction.write { entries += Entry(order.id, requestedBy, requestDigest) }
        return ok(Unit)
    }
}
