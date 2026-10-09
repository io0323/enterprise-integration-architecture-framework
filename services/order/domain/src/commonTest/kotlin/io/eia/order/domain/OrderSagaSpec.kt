package io.eia.order.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** シナリオに沿って [OrderSagaRules.decide] を順に適用し、通った状態・送ったコマンド・注文の状態を返す。 */
private data class Run(
    val states: List<SagaState>,
    val commands: List<SagaCommand>,
    val orderStatuses: List<OrderStatus>,
    val failure: SagaFailure?,
)

private fun run(vararg signals: SagaSignal): Run {
    var state = OrderSagaRules.initial
    val states = mutableListOf(state)
    val commands = mutableListOf(checkNotNull(state.command))
    val orders = mutableListOf<OrderStatus>()
    var failure: SagaFailure? = null
    for (signal in signals) {
        when (val decision = OrderSagaRules.decide(state, signal)) {
            is SagaDecision.Advance -> {
                state = decision.transition.to
                states += state
                decision.command?.let { commands += it }
                decision.transition.orderStatus?.let { orders += it }
                decision.transition.failure?.let { failure = it }
            }

            is SagaDecision.Resend -> {
                commands += decision.command
            }

            SagaDecision.Ignore -> {}
        }
    }
    return Run(states, commands, orders, failure)
}

class OrderSagaSpec :
    FunSpec({
        test("正常: 在庫の引当 → 決済の承認(注文は CONFIRMED)→ 出荷(注文は SHIPPED)で完了する") {
            run(SagaSignal.STOCK_RESERVED, SagaSignal.PAYMENT_AUTHORIZED, SagaSignal.SHIPMENT_SHIPPED) shouldBe
                Run(
                    listOf(SagaState.RESERVING_STOCK, SagaState.AUTHORIZING_PAYMENT, SagaState.ARRANGING_SHIPMENT, SagaState.COMPLETED),
                    listOf(SagaCommand.RESERVE_STOCK, SagaCommand.AUTHORIZE_PAYMENT, SagaCommand.ARRANGE_SHIPMENT),
                    listOf(OrderStatus.CONFIRMED, OrderStatus.SHIPPED),
                    null,
                )
        }

        test("在庫不足: 何も引き当てていないので、補償なしで終わり、注文は CANCELLED") {
            run(SagaSignal.STOCK_RESERVATION_REJECTED) shouldBe
                Run(
                    listOf(SagaState.RESERVING_STOCK, SagaState.COMPENSATED),
                    listOf(SagaCommand.RESERVE_STOCK),
                    listOf(OrderStatus.CANCELLED),
                    SagaFailure.STOCK_UNAVAILABLE,
                )
        }

        test("決済の失敗: 在庫を解放してから、注文は CANCELLED") {
            run(SagaSignal.STOCK_RESERVED, SagaSignal.PAYMENT_DECLINED, SagaSignal.STOCK_RELEASED) shouldBe
                Run(
                    listOf(SagaState.RESERVING_STOCK, SagaState.AUTHORIZING_PAYMENT, SagaState.RELEASING_STOCK, SagaState.COMPENSATED),
                    listOf(SagaCommand.RESERVE_STOCK, SagaCommand.AUTHORIZE_PAYMENT, SagaCommand.RELEASE_STOCK),
                    listOf(OrderStatus.CANCELLED),
                    SagaFailure.PAYMENT_DECLINED,
                )
        }

        test("決済の期限切れ: 承認されたかどうか分からないので、承認の取消 → 在庫の解放の順に補償する") {
            run(SagaSignal.STOCK_RESERVED, SagaSignal.STEP_TIMED_OUT, SagaSignal.PAYMENT_VOIDED, SagaSignal.STOCK_RELEASED).let {
                it.commands shouldBe
                    listOf(SagaCommand.RESERVE_STOCK, SagaCommand.AUTHORIZE_PAYMENT, SagaCommand.VOID_PAYMENT, SagaCommand.RELEASE_STOCK)
                it.states.last() shouldBe SagaState.COMPENSATED
                it.orderStatuses shouldBe listOf(OrderStatus.CANCELLED)
                it.failure shouldBe SagaFailure.TIMED_OUT
            }
        }

        test("在庫の引当の期限切れ: 引き当てたかどうか分からないので、解放を送る") {
            run(SagaSignal.STEP_TIMED_OUT, SagaSignal.STOCK_RELEASED).commands shouldBe
                listOf(SagaCommand.RESERVE_STOCK, SagaCommand.RELEASE_STOCK)
        }

        test("出荷の拒否・出荷の期限切れ: 逆の順に 1 つずつ補償し、確定した注文も CANCELLED にする") {
            run(
                SagaSignal.STOCK_RESERVED,
                SagaSignal.PAYMENT_AUTHORIZED,
                SagaSignal.SHIPMENT_REJECTED,
                SagaSignal.PAYMENT_VOIDED,
                SagaSignal.STOCK_RELEASED,
            ).let {
                it.commands.drop(3) shouldBe listOf(SagaCommand.VOID_PAYMENT, SagaCommand.RELEASE_STOCK)
                it.orderStatuses shouldBe listOf(OrderStatus.CONFIRMED, OrderStatus.CANCELLED)
                it.failure shouldBe SagaFailure.SHIPMENT_REJECTED
            }
            run(
                SagaSignal.STOCK_RESERVED,
                SagaSignal.PAYMENT_AUTHORIZED,
                SagaSignal.STEP_TIMED_OUT,
                SagaSignal.SHIPMENT_CANCELLED,
                SagaSignal.PAYMENT_VOIDED,
                SagaSignal.STOCK_RELEASED,
            ).let {
                it.commands.drop(3) shouldBe listOf(SagaCommand.CANCEL_SHIPMENT, SagaCommand.VOID_PAYMENT, SagaCommand.RELEASE_STOCK)
                it.states.last() shouldBe SagaState.COMPENSATED
            }
        }

        test("出荷の取消を頼んだが出荷済みだった: 補償をやめて完了に進む(決済の取消・在庫の解放は送らない)") {
            run(
                SagaSignal.STOCK_RESERVED,
                SagaSignal.PAYMENT_AUTHORIZED,
                SagaSignal.STEP_TIMED_OUT,
                SagaSignal.SHIPMENT_ALREADY_SHIPPED,
            ).let {
                it.states.last() shouldBe SagaState.COMPLETED
                it.commands.last() shouldBe SagaCommand.CANCEL_SHIPMENT
                it.orderStatuses shouldBe listOf(OrderStatus.CONFIRMED, OrderStatus.SHIPPED)
            }
        }

        test("補償の段の期限切れは、状態を変えずに同じコマンドを送り直す(何度でも)") {
            run(SagaSignal.STEP_TIMED_OUT, SagaSignal.STEP_TIMED_OUT, SagaSignal.STEP_TIMED_OUT).let {
                it.states shouldBe listOf(SagaState.RESERVING_STOCK, SagaState.RELEASING_STOCK)
                it.commands shouldBe
                    listOf(SagaCommand.RESERVE_STOCK, SagaCommand.RELEASE_STOCK, SagaCommand.RELEASE_STOCK, SagaCommand.RELEASE_STOCK)
            }
            OrderSagaRules.decide(SagaState.CANCELLING_SHIPMENT, SagaSignal.STEP_TIMED_OUT) shouldBe
                SagaDecision.Resend(SagaState.CANCELLING_SHIPMENT)
            OrderSagaRules
                .decide(
                    SagaState.VOIDING_PAYMENT,
                    SagaSignal.STEP_TIMED_OUT,
                ).shouldBeInstanceOf<SagaDecision.Resend>()
                .command shouldBe
                SagaCommand.VOID_PAYMENT
        }

        test("今の段に関係しない結果(重複・遅れて届いた前の段の結果・終端の後)は無視する") {
            // 期限切れで補償に入った後に、遅れて引当の結果が届く
            OrderSagaRules.decide(SagaState.RELEASING_STOCK, SagaSignal.STOCK_RESERVED) shouldBe SagaDecision.Ignore
            // 出荷の取消の結果を待つ間に、出荷の結果が届く(取消の結果が ALREADY_SHIPPED で届くので、それで判定する)
            OrderSagaRules.decide(SagaState.CANCELLING_SHIPMENT, SagaSignal.SHIPMENT_SHIPPED) shouldBe SagaDecision.Ignore
            OrderSagaRules.decide(SagaState.AUTHORIZING_PAYMENT, SagaSignal.STOCK_RESERVED) shouldBe SagaDecision.Ignore
            SagaState.entries.filter { it.isTerminal }.forEach { terminal ->
                SagaSignal.entries.forEach { signal -> OrderSagaRules.decide(terminal, signal) shouldBe SagaDecision.Ignore }
            }
        }

        test("遷移表の整合: 終端からは遷移しない・注文の状態の遷移は注文の遷移表にある・補償に入る遷移だけが理由を持つ") {
            SagaState.entries.filter { it.isTerminal } shouldBe listOf(SagaState.COMPLETED, SagaState.COMPENSATED)
            OrderSagaRules.transitions.none { it.from.isTerminal } shouldBe true
            OrderSagaRules.transitions
                .map { it.from to it.signal }
                .toSet()
                .size shouldBe OrderSagaRules.transitions.size
            // Saga が注文を移す順(PLACED → CONFIRMED → SHIPPED、PLACED / CONFIRMED → CANCELLED)は、注文の遷移表で許される
            OrderStatus.PLACED.next.contains(OrderStatus.CONFIRMED) shouldBe true
            OrderStatus.CONFIRMED.next.containsAll(listOf(OrderStatus.SHIPPED, OrderStatus.CANCELLED)) shouldBe true
            OrderStatus.PLACED.next.contains(OrderStatus.CANCELLED) shouldBe true
            OrderSagaRules.transitions.filter { it.failure != null }.forEach { t ->
                (t.to.isCompensating || t.to == SagaState.COMPENSATED) shouldBe true
                t.from.isCompensating shouldBe false
            }
            // すべての段は、期限切れで何かをする(前進の段は補償へ、補償の段は送り直し)
            SagaState.entries.filterNot { it.isTerminal }.forEach { state ->
                (OrderSagaRules.decide(state, SagaSignal.STEP_TIMED_OUT) is SagaDecision.Ignore) shouldBe false
            }
        }
    })
