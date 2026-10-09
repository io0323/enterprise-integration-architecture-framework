package io.eia.order.domain

/**
 * 注文 Saga(Orchestration 方式。Framework 4.2・13。ADR-0029)の状態。遷移表は docs/architecture/order-saga.md と一致させる
 * ([OrderSagaRules.transitions] で固定し、テストで表と照合する)。
 *
 * 前進の段(在庫の引当 → 決済の承認 → 出荷の手配)と、補償の段(出荷の取消 → 決済の承認の取消 → 在庫の解放。前進の逆の順に 1 つずつ)を持つ。
 * 各段に入るときに、その段の [command] を送り、結果(または期限切れ)を待つ。
 *
 * @property command この段に入るときに送るコマンド。終端は null
 */
public enum class SagaState(
    public val command: SagaCommand?,
) {
    /** 在庫の引当を待つ(Saga の始まり。注文の受付と同じトランザクションで作る)。 */
    RESERVING_STOCK(SagaCommand.RESERVE_STOCK),

    /** 決済の承認を待つ。 */
    AUTHORIZING_PAYMENT(SagaCommand.AUTHORIZE_PAYMENT),

    /** 出荷を待つ(注文は CONFIRMED)。 */
    ARRANGING_SHIPMENT(SagaCommand.ARRANGE_SHIPMENT),

    /** 完了(終端。注文は SHIPPED)。 */
    COMPLETED(null),

    /** 補償: 出荷の取消を待つ。出荷済みと答えたら、補償をやめて完了に進む。 */
    CANCELLING_SHIPMENT(SagaCommand.CANCEL_SHIPMENT),

    /** 補償: 決済の承認の取消を待つ。 */
    VOIDING_PAYMENT(SagaCommand.VOID_PAYMENT),

    /** 補償: 在庫の解放を待つ。 */
    RELEASING_STOCK(SagaCommand.RELEASE_STOCK),

    /** 補償の完了(終端。注文は CANCELLED)。 */
    COMPENSATED(null),
    ;

    /** 終端(ここからは遷移しない)。 */
    public val isTerminal: Boolean get() = command == null

    /** 補償の段。期限切れでは、同じコマンドを送り直す(補償は必ず終える。ADR-0029 §3)。 */
    public val isCompensating: Boolean get() = this in COMPENSATING

    private companion object {
        val COMPENSATING = setOf(CANCELLING_SHIPMENT, VOIDING_PAYMENT, RELEASING_STOCK)
    }
}

/** Saga が参加者に送るコマンド(契約のトピックは adapters が対応づける)。 */
public enum class SagaCommand {
    RESERVE_STOCK,
    AUTHORIZE_PAYMENT,
    ARRANGE_SHIPMENT,
    CANCEL_SHIPMENT,
    VOID_PAYMENT,
    RELEASE_STOCK,
}

/** Saga が受け取る結果(参加者の返信のイベント)と、段の期限切れ。 */
public enum class SagaSignal {
    STOCK_RESERVED,
    STOCK_RESERVATION_REJECTED,
    STOCK_RELEASED,
    PAYMENT_AUTHORIZED,
    PAYMENT_DECLINED,
    PAYMENT_VOIDED,
    SHIPMENT_SHIPPED,
    SHIPMENT_REJECTED,

    /** 出荷を取り消した、または手配がなかった(取消済みの印を残した)。 */
    SHIPMENT_CANCELLED,

    /** 出荷の取消を頼んだが、すでに出荷していた(取り消していない)。 */
    SHIPMENT_ALREADY_SHIPPED,

    /** 段の期限(DB の時計。ADR-0029 §6)を過ぎた。 */
    STEP_TIMED_OUT,
}

/** Saga が失敗した理由(補償に入ったきっかけ)。注文の取消の理由になる。 */
public enum class SagaFailure {
    STOCK_UNAVAILABLE,
    PAYMENT_DECLINED,
    SHIPMENT_REJECTED,
    TIMED_OUT,
}

/**
 * 遷移表の 1 行。
 *
 * @property orderStatus この遷移で注文を移す状態(移さなければ null)
 * @property failure この遷移で補償に入るときの理由(補償に入らなければ null)
 */
public data class SagaTransition(
    public val from: SagaState,
    public val signal: SagaSignal,
    public val to: SagaState,
    public val orderStatus: OrderStatus? = null,
    public val failure: SagaFailure? = null,
)

/** [OrderSagaRules.decide] の判定。 */
public sealed interface SagaDecision {
    /** [transition] のとおりに進み、`to` の段のコマンド(あれば)を送る。 */
    public data class Advance(
        public val transition: SagaTransition,
    ) : SagaDecision {
        public val command: SagaCommand? get() = transition.to.command
    }

    /** 補償の段の期限切れ: 状態は変えず、同じコマンドを送り直す。 */
    public data class Resend(
        public val state: SagaState,
    ) : SagaDecision {
        public val command: SagaCommand get() = checkNotNull(state.command)
    }

    /**
     * 今の段に関係しない結果(遅れて届いた結果・重複・終端の後の結果)。何もしない(At-Least-Once の重複と、
     * 期限切れの後に届いた前の段の結果を吸収する。参加者は取消済みの印で整合をとる。ADR-0029 §5)。
     */
    public data object Ignore : SagaDecision
}

/** 注文 Saga の遷移の規則(純粋な関数。永続化・送信は application と adapters が行う)。 */
public object OrderSagaRules {
    public val initial: SagaState = SagaState.RESERVING_STOCK

    /** 遷移表(docs/architecture/order-saga.md の「遷移表」と一致させる)。補償の段の期限切れ(送り直し)は含めない。 */
    public val transitions: List<SagaTransition> =
        listOf(
            SagaTransition(SagaState.RESERVING_STOCK, SagaSignal.STOCK_RESERVED, SagaState.AUTHORIZING_PAYMENT),
            SagaTransition(
                SagaState.RESERVING_STOCK,
                SagaSignal.STOCK_RESERVATION_REJECTED,
                SagaState.COMPENSATED,
                OrderStatus.CANCELLED,
                SagaFailure.STOCK_UNAVAILABLE,
            ),
            SagaTransition(
                SagaState.RESERVING_STOCK,
                SagaSignal.STEP_TIMED_OUT,
                SagaState.RELEASING_STOCK,
                failure = SagaFailure.TIMED_OUT,
            ),
            SagaTransition(
                SagaState.AUTHORIZING_PAYMENT,
                SagaSignal.PAYMENT_AUTHORIZED,
                SagaState.ARRANGING_SHIPMENT,
                OrderStatus.CONFIRMED,
            ),
            SagaTransition(
                SagaState.AUTHORIZING_PAYMENT,
                SagaSignal.PAYMENT_DECLINED,
                SagaState.RELEASING_STOCK,
                failure = SagaFailure.PAYMENT_DECLINED,
            ),
            SagaTransition(
                SagaState.AUTHORIZING_PAYMENT,
                SagaSignal.STEP_TIMED_OUT,
                SagaState.VOIDING_PAYMENT,
                failure = SagaFailure.TIMED_OUT,
            ),
            SagaTransition(SagaState.ARRANGING_SHIPMENT, SagaSignal.SHIPMENT_SHIPPED, SagaState.COMPLETED, OrderStatus.SHIPPED),
            SagaTransition(
                SagaState.ARRANGING_SHIPMENT,
                SagaSignal.SHIPMENT_REJECTED,
                SagaState.VOIDING_PAYMENT,
                failure = SagaFailure.SHIPMENT_REJECTED,
            ),
            SagaTransition(
                SagaState.ARRANGING_SHIPMENT,
                SagaSignal.STEP_TIMED_OUT,
                SagaState.CANCELLING_SHIPMENT,
                failure = SagaFailure.TIMED_OUT,
            ),
            SagaTransition(SagaState.CANCELLING_SHIPMENT, SagaSignal.SHIPMENT_CANCELLED, SagaState.VOIDING_PAYMENT),
            SagaTransition(SagaState.CANCELLING_SHIPMENT, SagaSignal.SHIPMENT_ALREADY_SHIPPED, SagaState.COMPLETED, OrderStatus.SHIPPED),
            SagaTransition(SagaState.VOIDING_PAYMENT, SagaSignal.PAYMENT_VOIDED, SagaState.RELEASING_STOCK),
            SagaTransition(SagaState.RELEASING_STOCK, SagaSignal.STOCK_RELEASED, SagaState.COMPENSATED, OrderStatus.CANCELLED),
        )

    private val byKey: Map<Pair<SagaState, SagaSignal>, SagaTransition> = transitions.associateBy { it.from to it.signal }

    /** [state] で [signal] を受け取ったときの判定。 */
    public fun decide(
        state: SagaState,
        signal: SagaSignal,
    ): SagaDecision {
        byKey[state to signal]?.let { return SagaDecision.Advance(it) }
        return if (signal == SagaSignal.STEP_TIMED_OUT && state.isCompensating) SagaDecision.Resend(state) else SagaDecision.Ignore
    }
}
