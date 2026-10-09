package io.eia.inventory.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** 引当の指示への返事(契約の `inventory.stock.reserved.v1` / `reservation-rejected.v1`)。 */
public sealed interface ReserveReply {
    public data object Reserved : ReserveReply

    public data class Rejected(
        public val reason: RejectionReason,
    ) : ReserveReply
}

/** [InventoryRules.reserve] の判定。 */
public sealed interface ReserveDecision {
    public val reply: ReserveReply

    /** 引き当てる: [reservation](RESERVED)を作り、SKU ごとに [increments] だけ引当の数を増やす。 */
    public data class Reserve(
        public val reservation: Reservation,
        public val increments: Map<String, Long>,
    ) : ReserveDecision {
        override val reply: ReserveReply get() = ReserveReply.Reserved
    }

    /** 引き当てない: [reservation](REJECTED)を作る。在庫は変えない。 */
    public data class Reject(
        public val reservation: Reservation,
    ) : ReserveDecision {
        override val reply: ReserveReply get() = ReserveReply.Rejected(checkNotNull(reservation.rejection))
    }

    /** 同じ Saga の記録がある: 何も変えず、記録から決まる返事を返し直す(Orchestrator の送り直し・作り直しに答える)。 */
    public data class Replay(
        override val reply: ReserveReply,
    ) : ReserveDecision
}

/** [InventoryRules.release] の判定。 */
public sealed interface ReleaseDecision {
    public val outcome: ReleaseOutcome

    /** 解放する: 記録を [released](RELEASED)にし、SKU ごとに [decrements] だけ引当の数を減らす。 */
    public data class Release(
        public val released: Reservation,
        public val decrements: Map<String, Long>,
    ) : ReleaseDecision {
        override val outcome: ReleaseOutcome get() = ReleaseOutcome.RELEASED
    }

    /** 記録がない: 「取消済み」の印 [marker] を作る(後から届いた引当の指示を拒否するため。ADR-0029 §5)。 */
    public data class MarkReleasedBeforeReservation(
        public val marker: Reservation,
    ) : ReleaseDecision {
        override val outcome: ReleaseOutcome get() = ReleaseOutcome.NOT_RESERVED
    }

    /** 何も変えず、記録から決まる結果を返し直す。 */
    public data class Replay(
        override val outcome: ReleaseOutcome,
    ) : ReleaseDecision
}

/** 在庫の引当と解放の規則(純粋な関数。永続化とロックは application と adapters が行う)。 */
public object InventoryRules {
    /**
     * 引当の指示の判定。
     *
     * - 同じ Saga ID の記録([existing])があれば、それで決まる返事を返し直す。印(解放が先に届いた)と解放済みなら `ALREADY_RELEASED` で拒否する。
     * - 初めてなら、明細を SKU ごとに合計し、[stock](対象の SKU をロックして読んだもの)で判定する。
     *   **全部の明細を引き当てられるときだけ引き当てる**(1 つでも足りなければ何も引き当てない)。台帳にない SKU は `UNKNOWN_SKU`(在庫不足より優先)。
     */
    public fun reserve(
        existing: Reservation?,
        sagaId: String,
        orderId: String,
        lines: List<StockLine>,
        stock: Map<String, StockLevel>,
    ): ReserveDecision {
        if (existing != null) return ReserveDecision.Replay(replyOf(existing))
        val requested = totals(lines)
        val rejection =
            when {
                requested.keys.any { it !in stock } -> RejectionReason.UNKNOWN_SKU
                requested.any { (sku, quantity) -> stock.getValue(sku).available < quantity } -> RejectionReason.INSUFFICIENT_STOCK
                else -> null
            }
        return if (rejection == null) {
            ReserveDecision.Reserve(Reservation(sagaId, orderId, ReservationStatus.RESERVED, lines = lines), requested)
        } else {
            ReserveDecision.Reject(Reservation(sagaId, orderId, ReservationStatus.REJECTED, rejection))
        }
    }

    /**
     * 解放の指示の判定。記録がなければ印を作り、引き当てていれば解放する。拒否した・解放済み・印なら何も変えずに返し直す
     * (何度届いても在庫は 1 回だけ戻る)。
     */
    public fun release(
        existing: Reservation?,
        sagaId: String,
        orderId: String,
    ): ReleaseDecision =
        when (existing?.status) {
            null -> {
                ReleaseDecision.MarkReleasedBeforeReservation(Reservation(sagaId, orderId, ReservationStatus.RELEASED_BEFORE_RESERVATION))
            }

            ReservationStatus.RESERVED -> {
                ReleaseDecision.Release(existing.copy(status = ReservationStatus.RELEASED), totals(existing.lines))
            }

            ReservationStatus.RELEASED -> {
                ReleaseDecision.Replay(ReleaseOutcome.RELEASED)
            }

            ReservationStatus.REJECTED, ReservationStatus.RELEASED_BEFORE_RESERVATION -> {
                ReleaseDecision.Replay(ReleaseOutcome.NOT_RESERVED)
            }
        }

    private fun replyOf(existing: Reservation): ReserveReply =
        when (existing.status) {
            ReservationStatus.RESERVED -> {
                ReserveReply.Reserved
            }

            ReservationStatus.REJECTED -> {
                ReserveReply.Rejected(checkNotNull(existing.rejection))
            }

            ReservationStatus.RELEASED, ReservationStatus.RELEASED_BEFORE_RESERVATION -> {
                ReserveReply.Rejected(
                    RejectionReason.ALREADY_RELEASED,
                )
            }
        }

    /** SKU ごとの数量の合計(同じ SKU の明細が複数あってもよい)。 */
    private fun totals(lines: List<StockLine>): Map<String, Long> =
        lines.groupBy { it.sku }.mapValues { (_, same) -> same.sumOf { it.quantity } }
}

/**
 * 終わった引当の記録(印を含む)の保持期間(ADR-0029 §5)。既定は 30 日で、[MINIMUM] より短くはできない。
 *
 * 遅れて届いた引当の指示を拒否するため、印はコマンドのトピック(7 日)と DLQ(7 日)の保持期間の合計([MINIMUM])より長く残す。
 */
public data class SettledRetention private constructor(
    public val value: Duration,
) {
    public companion object {
        public val MINIMUM: Duration = 14.days
        public val DEFAULT: SettledRetention = SettledRetention(30.days)

        public fun of(
            value: Duration,
            field: String = "retention",
        ): Result<SettledRetention, ValidationError> =
            if (value >= MINIMUM) {
                ok(SettledRetention(value))
            } else {
                err(ValidationError(listOf(FieldViolation(field, "$MINIMUM 以上にしてください(コマンドのトピックと DLQ の保持期間の合計。ADR-0029 §5)"))))
            }
    }
}
