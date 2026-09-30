package io.eia.order.domain

/**
 * 注文の状態。遷移表は docs/architecture/order-state-machine.md と一致させる([allowedTransitions] で固定し、テストで表と照合する)。
 *
 * Saga の内部の状態(在庫の引当済み・決済の承認済み・補償中など)は、注文の状態とは別に P07 の saga テーブルで持つ。
 */
public enum class OrderStatus {
    /** 受け付けた。Saga(在庫の引当・決済の承認)の完了を待つ。 */
    PLACED,

    /** 在庫の引当と決済の承認が終わり、確定した。 */
    CONFIRMED,

    /** 出荷した。 */
    SHIPPED,

    /** 配達を完了した(終端)。 */
    DELIVERED,

    /** 取り消した(終端)。確定の前の Saga の失敗、または出荷の前の取消。 */
    CANCELLED,
    ;

    /** この状態から遷移できる状態。同じ状態への遷移は含めない(NoOp として別に扱う。[Order.transitionTo])。 */
    public val next: Set<OrderStatus> get() = allowedTransitions.getValue(this)

    /** 終端(ここからは遷移しない)。 */
    public val isTerminal: Boolean get() = next.isEmpty()

    public companion object {
        /** 遷移表。 */
        public val allowedTransitions: Map<OrderStatus, Set<OrderStatus>> =
            mapOf(
                PLACED to setOf(CONFIRMED, CANCELLED),
                CONFIRMED to setOf(SHIPPED, CANCELLED),
                SHIPPED to setOf(DELIVERED),
                DELIVERED to emptySet(),
                CANCELLED to emptySet(),
            )
    }
}
