package io.eia.order.adapters.out.saga

import io.eia.order.application.port.outbound.SagaObserver
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaSignal
import io.eia.order.domain.SagaState
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * 注文 Saga のメトリクス(ADR-0029 §3)。OTLP → Collector → Prometheus(`eia_saga_transitions_total` など)。
 *
 * | 名前 | 種類 | 属性 |
 * |---|---|---|
 * | `eia.saga.transitions` | counter | `from`・`to`・`failure`(補償の理由。なければ `none`) |
 * | `eia.saga.resends` | counter | `state`(補償のコマンドの送り直し) |
 * | `eia.saga.stalled` | counter | `state`(送り直しが [stallAfterResends] 回を超えた。アラート `OrderSagaCompensationStalled`) |
 * | `eia.saga.ignored` | counter | `state`・`signal`(今の段に関係しない結果) |
 */
public class SagaMetrics(
    meter: Meter,
    private val stallAfterResends: Int,
) : SagaObserver {
    private val transitions: LongCounter =
        meter
            .counterBuilder("eia.saga.transitions")
            .setDescription("Saga の状態の遷移の件数")
            .setUnit("{transition}")
            .build()
    private val resends: LongCounter =
        meter
            .counterBuilder("eia.saga.resends")
            .setDescription("補償のコマンドを送り直した回数")
            .setUnit("{resend}")
            .build()
    private val stalled: LongCounter =
        meter
            .counterBuilder("eia.saga.stalled")
            .setDescription("補償の送り直しが上限の回数を超えた件数")
            .setUnit("{resend}")
            .build()
    private val ignored: LongCounter =
        meter
            .counterBuilder("eia.saga.ignored")
            .setDescription("今の段に関係しない結果を無視した件数")
            .setUnit("{signal}")
            .build()

    override fun transitioned(
        saga: Saga,
        from: SagaState,
    ) {
        transitions.add(1, Attributes.of(FROM, from.name, TO, saga.state.name, FAILURE, saga.failure?.name ?: NONE))
    }

    override fun resent(saga: Saga) {
        val attributes = Attributes.of(STATE, saga.state.name)
        resends.add(1, attributes)
        if (saga.resends > stallAfterResends) stalled.add(1, attributes)
    }

    override fun ignored(
        state: SagaState,
        signal: SagaSignal,
    ) {
        ignored.add(1, Attributes.of(STATE, state.name, SIGNAL, signal.name))
    }

    private companion object {
        const val NONE = "none"
        val FROM: AttributeKey<String> = AttributeKey.stringKey("from")
        val TO: AttributeKey<String> = AttributeKey.stringKey("to")
        val FAILURE: AttributeKey<String> = AttributeKey.stringKey("failure")
        val STATE: AttributeKey<String> = AttributeKey.stringKey("state")
        val SIGNAL: AttributeKey<String> = AttributeKey.stringKey("signal")
    }
}
