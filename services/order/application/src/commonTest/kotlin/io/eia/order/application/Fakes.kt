package io.eia.order.application

import io.eia.order.application.port.inbound.RequestedBy
import io.eia.order.application.port.outbound.OrderAuditTrail
import io.eia.order.application.port.outbound.OrderEventOutbox
import io.eia.order.application.port.outbound.OrderIdGenerator
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.order.application.port.outbound.ProcessedReplies
import io.eia.order.application.port.outbound.SagaCommandOutbox
import io.eia.order.application.port.outbound.SagaIdGenerator
import io.eia.order.application.port.outbound.SagaStore
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.order.domain.RestoredLine
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaCommand
import io.eia.order.domain.SagaFailure
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration

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

/** メモリ上の Outbox。書いた注文の ID を、トランザクションが確定したときだけ残す。[failWith] で次の書き込みを失敗させる。 */
internal class FakeOrderEventOutbox(
    private val transaction: FakeTransactionRunner,
) : OrderEventOutbox {
    val placed = mutableListOf<OrderId>()
    val cancelled = mutableListOf<Pair<OrderId, SagaFailure?>>()
    var failWith: DomainError? = null

    override suspend fun orderPlaced(order: Order): Result<Unit, DomainError> {
        failWith?.let { return err(it) }
        transaction.write { placed += order.id }
        return ok(Unit)
    }

    override suspend fun orderCancelled(
        order: Order,
        reason: SagaFailure?,
    ): Result<Unit, DomainError> {
        failWith?.let { return err(it) }
        transaction.write { cancelled += order.id to reason }
        return ok(Unit)
    }
}

/** メモリ上の Saga の記録。期限は「次の段の期限の長さ」だけを記録する(時刻は DB の時計で決まるため)。[expired] を [lockExpired] が返す。 */
internal class FakeSagaStore(
    private val transaction: FakeTransactionRunner,
) : SagaStore {
    val sagas = mutableMapOf<String, Saga>()
    val timeouts = mutableMapOf<String, Duration?>()
    val expired = mutableListOf<String>()
    var failWith: DomainError? = null

    override suspend fun insert(
        saga: Saga,
        stepTimeout: Duration?,
    ): Result<Unit, DomainError> {
        failWith?.let { return err(it) }
        transaction.write {
            sagas[saga.id] = saga
            timeouts[saga.id] = stepTimeout
        }
        return ok(Unit)
    }

    override suspend fun findForUpdate(sagaId: String): Result<Saga?, DomainError> = ok(sagas[sagaId])

    override suspend fun save(
        saga: Saga,
        stepTimeout: Duration?,
    ): Result<Unit, DomainError> {
        failWith?.let { return err(it) }
        transaction.write {
            sagas[saga.id] = saga
            timeouts[saga.id] = stepTimeout
        }
        return ok(Unit)
    }

    override suspend fun lockExpired(limit: Int): Result<List<Saga>, DomainError> {
        val batch = expired.take(limit).map { sagas.getValue(it) }
        repeat(batch.size) { expired.removeFirst() }
        return ok(batch)
    }
}

/** メモリ上のコマンドの Outbox。書いたコマンドを、トランザクションが確定したときだけ残す。 */
internal class FakeSagaCommands(
    private val transaction: FakeTransactionRunner,
) : SagaCommandOutbox {
    val sent = mutableListOf<Pair<String, SagaCommand>>()
    var failWith: DomainError? = null

    override suspend fun send(
        command: SagaCommand,
        saga: Saga,
        order: Order,
    ): Result<Unit, DomainError> {
        failWith?.let { return err(it) }
        transaction.write { sent += saga.id to command }
        return ok(Unit)
    }
}

/** メモリ上の冪等消費の記録。 */
internal class FakeProcessedReplies(
    private val transaction: FakeTransactionRunner,
) : ProcessedReplies {
    private val seen = mutableSetOf<String>()

    override suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError> {
        val first = messageId !in seen
        if (first) transaction.write { seen += messageId }
        return ok(first)
    }
}

/** `saga-1`, `saga-2`, ... を順に返す。 */
internal class SequentialSagaIds : SagaIdGenerator {
    private var issued = 0

    override fun next(): String = "saga-${++issued}"
}
