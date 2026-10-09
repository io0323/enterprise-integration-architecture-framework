package io.eia.order.application.usecase

import io.eia.order.application.port.outbound.OrderEventOutbox
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.SagaCommandOutbox
import io.eia.order.application.port.outbound.SagaIdGenerator
import io.eia.order.application.port.outbound.SagaObserver
import io.eia.order.application.port.outbound.SagaStore
import io.eia.order.domain.Order
import io.eia.order.domain.OrderSagaRules
import io.eia.order.domain.OrderStatus
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaDecision
import io.eia.order.domain.SagaSignal
import io.eia.order.domain.SagaState
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Saga の段の期限(ADR-0029 §6)。値は期間だけで、期限の時刻は DB の時計で決める。
 *
 * @property step 前進の段(在庫の引当・決済の承認・出荷の手配)の期限
 * @property compensation 補償の段のコマンドを送り直す間隔
 * @property stallAfterResends 補償の送り直しがこの回数を超えたら、止まっているとみなす(アラート。§3)
 */
public data class SagaTimeouts(
    public val step: Duration = 30.seconds,
    public val compensation: Duration = 30.seconds,
    public val stallAfterResends: Int = 5,
) {
    init {
        require(step.isPositive() && compensation.isPositive()) { "期限は正の期間にしてください" }
        require(stallAfterResends >= 1) { "stallAfterResends は 1 以上にしてください" }
    }

    /** [state] に入ったときの期限。終端は null(期限なし)。 */
    public fun of(state: SagaState): Duration? =
        when {
            state.isTerminal -> null
            state.isCompensating -> compensation
            else -> step
        }
}

/**
 * Saga の判定([OrderSagaRules])を、注文・Saga の記録・コマンド・注文のイベントに反映する(ADR-0029 §1・§3)。
 * どの操作も呼び出し元のトランザクションの中で行う(Saga と注文の状態・次のコマンド・イベントを 1 つのトランザクションで書く)。
 */
@Suppress("LongParameterList") // 1 つのトランザクションに入る書き込み先(Port)ごとの依存と、期限・通知
public class SagaCoordinator(
    private val sagas: SagaStore,
    private val orders: OrderRepository,
    private val commands: SagaCommandOutbox,
    private val events: OrderEventOutbox,
    private val ids: SagaIdGenerator,
    private val timeouts: SagaTimeouts = SagaTimeouts(),
    private val observer: SagaObserver = SagaObserver.NONE,
) {
    /** 注文の受付で Saga を始める(`RESERVING_STOCK`)。在庫の引当のコマンドを書く。 */
    public suspend fun start(order: Order): Result<Saga, DomainError> {
        val saga = Saga(ids.next(), order.id, OrderSagaRules.initial)
        return sagas
            .insert(saga, timeouts.of(saga.state))
            .flatMap { commands.send(checkNotNull(saga.state.command), saga, order) }
            .map { saga }
    }

    /** [saga](ロックして読んだもの)で [signal] を受け取ったときの判定を反映する。 */
    public suspend fun handle(
        saga: Saga,
        signal: SagaSignal,
    ): Result<SagaDecision, DomainError> {
        val decision = OrderSagaRules.decide(saga.state, signal)
        return when (decision) {
            is SagaDecision.Advance -> advance(saga, decision)
            is SagaDecision.Resend -> resend(saga, decision)
            SagaDecision.Ignore -> ok(Unit).also { observer.ignored(saga.state, signal) }
        }.map { decision }
    }

    private suspend fun advance(
        saga: Saga,
        decision: SagaDecision.Advance,
    ): Result<Unit, DomainError> {
        val transition = decision.transition
        val next = saga.copy(state = transition.to, failure = transition.failure ?: saga.failure, resends = 0)
        return order(saga)
            .flatMap { order -> moveOrder(order, transition.orderStatus) }
            .flatMap { order ->
                sagas
                    .save(next, timeouts.of(next.state))
                    .flatMap { decision.command?.let { commands.send(it, next, order) } ?: ok(Unit) }
                    .flatMap { if (next.state == SagaState.COMPENSATED) events.orderCancelled(order, next.failure) else ok(Unit) }
            }.map { observer.transitioned(next, saga.state) }
    }

    private suspend fun resend(
        saga: Saga,
        decision: SagaDecision.Resend,
    ): Result<Unit, DomainError> {
        val next = saga.copy(resends = saga.resends + 1)
        return order(saga)
            .flatMap { order -> sagas.save(next, timeouts.of(next.state)).flatMap { commands.send(decision.command, next, order) } }
            .map { observer.resent(next) }
    }

    /** 注文を [status] に移す(移さなければそのまま)。同じ状態なら NoOp(ADR: order-state-machine.md)。 */
    private suspend fun moveOrder(
        order: Order,
        status: OrderStatus?,
    ): Result<Order, DomainError> {
        if (status == null) return ok(order)
        return order.transitionTo(status).flatMap { transition ->
            when (transition) {
                is Order.Transition.Applied -> orders.update(transition.order)
                is Order.Transition.NoOp -> ok(transition.order)
            }
        }
    }

    private suspend fun order(saga: Saga): Result<Order, DomainError> =
        orders.findById(saga.orderId).flatMap { order ->
            order?.let { ok(it) } ?: err(UnexpectedError("Saga ${saga.id} の注文 ${saga.orderId} がありません"))
        }
}
