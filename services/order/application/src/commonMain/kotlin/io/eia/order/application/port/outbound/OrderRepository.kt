package io.eia.order.application.port.outbound

import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 注文の保存先(PostgreSQL の実装は P05 ④a)。例外は投げず、失敗は `Result` で返す(外部の例外は adapters で分類する)。
 *
 * 楽観的ロック(docs/architecture/order-state-machine.md): [update] は、渡した注文の `version` と保存されている版が一致するときだけ
 * 保存し、版を 1 増やす。一致しなければ [OrderVersionConflict] を返す。domain は版を運ぶだけで、版を増やすのはこの Port の実装。
 */
public interface OrderRepository {
    /** 新しい注文を保存する。同じ ID の注文があれば `ConflictError`。 */
    public suspend fun insert(order: Order): Result<Unit, DomainError>

    /** 注文を読む。なければ `null`。 */
    public suspend fun findById(id: OrderId): Result<Order?, DomainError>

    /**
     * 読んだ版([Order.version])のまま保存されていれば更新し、版を 1 増やした注文を返す。
     * 別の更新が先に確定していれば [OrderVersionConflict](呼び出し側は読み直して遷移表で判定し直す)。
     */
    public suspend fun update(order: Order): Result<Order, DomainError>
}

/**
 * 楽観的ロックの衝突。読んだ後に、別の更新(Saga の確定と利用者の取消など)が先に確定した。
 * 同じ操作をそのままリトライしても解決しないため NonRetryable とし、呼び出し側は最新の注文を読み直して判定し直す。
 */
public data class OrderVersionConflict(
    public val orderId: OrderId,
    public val expectedVersion: Long,
) : DomainError.NonRetryable {
    override val code: String get() = "order_version_conflict"
    override val message: String get() = "注文 $orderId は、読んだ版 $expectedVersion の後に更新されています"
}
