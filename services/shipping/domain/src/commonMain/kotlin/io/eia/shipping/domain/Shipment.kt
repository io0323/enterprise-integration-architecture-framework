package io.eia.shipping.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 識別子(Saga ID・注文 ID・出荷 ID・SKU)の長さの上限(DB の列と同じ)。 */
public const val MAX_IDENTIFIER_LENGTH: Int = 64

/** 出荷の明細(模擬は数を確かめるだけで、記録しない)。 */
public data class ShipmentLine(
    public val lineNumber: Int,
    public val sku: String,
    public val quantity: Long,
) {
    public companion object {
        public fun of(
            lineNumber: Int,
            sku: String,
            quantity: Long,
        ): Result<ShipmentLine, ValidationError> {
            val violations =
                buildList {
                    if (lineNumber < 1) add(FieldViolation("lines.lineNumber", "1 以上にしてください"))
                    if (sku.isBlank() ||
                        sku.length > MAX_IDENTIFIER_LENGTH
                    ) {
                        add(FieldViolation("lines.sku", "1〜$MAX_IDENTIFIER_LENGTH 文字にしてください"))
                    }
                    if (quantity < 1) add(FieldViolation("lines.quantity", "1 以上にしてください"))
                }
            return if (violations.isEmpty()) ok(ShipmentLine(lineNumber, sku, quantity)) else err(ValidationError(violations))
        }
    }
}

/** 届け先の国(ISO 3166-1 alpha-2)。模擬は国だけを持つ(住所の残りは記録しない。データの最小化。Framework 12.2)。 */
public data class Destination(
    public val countryCode: String,
) {
    public companion object {
        private val COUNTRY = Regex("^[A-Z]{2}$")

        public fun of(countryCode: String): Result<Destination, ValidationError> =
            if (COUNTRY.matches(countryCode)) {
                ok(Destination(countryCode))
            } else {
                err(ValidationError(listOf(FieldViolation("shippingAddress.countryCode", "ISO 3166-1 alpha-2 にしてください"))))
            }
    }
}

/** 出荷の記録の状態(Saga ID ごとに 1 行。ADR-0029 §5)。 */
public enum class ShipmentStatus {
    /** 出荷した(模擬は手配を受けたら直ちに出荷する。取り消せない)。 */
    SHIPPED,

    /** 出荷を受けなかった(何も出荷していない)。 */
    REJECTED,

    /** 「取消済み」の印: 手配より先に取消の指示が届いた。後から届いた手配の指示を拒否する(ADR-0029 §5)。 */
    CANCELLED_BEFORE_ARRANGEMENT,
    ;

    /** 終わった状態(保持期間を過ぎたら消してよい)。出荷した記録([SHIPPED])は業務の記録として残す。 */
    public val isSettled: Boolean get() = this != SHIPPED
}

/** 出荷を受けなかった理由(契約の `ShipmentRejectionReason`)。 */
public enum class RejectionReason {
    UNSUPPORTED_DESTINATION,
    ALREADY_CANCELLED,
}

/**
 * 取消の結果(契約の `ShipmentCancelOutcome`)。模擬は手配を受けたら直ちに出荷するため、手配の後の取消は常に [ALREADY_SHIPPED]。
 * [CANCELLED](手配したが出荷の前に取り消した)は、出荷までに時間がかかる実際の出荷のためにある(契約の値)。
 */
public enum class CancelOutcome {
    CANCELLED,
    NOT_ARRANGED,
    ALREADY_SHIPPED,
}

/** 出荷の記録(Saga ID が業務キー)。 */
public data class Shipment(
    public val sagaId: String,
    public val orderId: String,
    public val status: ShipmentStatus,
    public val shipmentId: String? = null,
    public val shippedAt: Instant? = null,
    public val rejection: RejectionReason? = null,
    public val destination: Destination? = null,
)

/** 出荷するときに付ける出荷 ID と出荷の時刻。 */
public data class ShipmentStamp(
    public val shipmentId: String,
    public val shippedAt: Instant,
)

/** 手配の指示への返事(契約の `shipping.shipment.shipped.v1` / `rejected.v1`)。 */
public sealed interface ArrangeReply {
    public data class Shipped(
        public val shipmentId: String,
        public val shippedAt: Instant,
    ) : ArrangeReply

    public data class Rejected(
        public val reason: RejectionReason,
    ) : ArrangeReply
}

/** [ShippingRules.arrange] の判定。 */
public sealed interface ArrangeDecision {
    public val reply: ArrangeReply

    /** 新しい記録 [shipment] を作る(出荷、または拒否)。 */
    public data class Record(
        public val shipment: Shipment,
        override val reply: ArrangeReply,
    ) : ArrangeDecision

    /** 同じ Saga の記録がある: 何も変えず、記録から決まる返事を返し直す。 */
    public data class Replay(
        override val reply: ArrangeReply,
    ) : ArrangeDecision
}

/** [ShippingRules.cancel] の判定。 */
public sealed interface CancelDecision {
    public val outcome: CancelOutcome

    /** 記録がない: 「取消済み」の印 [marker] を作る(後から届いた手配の指示を拒否するため。ADR-0029 §5)。 */
    public data class MarkCancelledBeforeArrangement(
        public val marker: Shipment,
    ) : CancelDecision {
        override val outcome: CancelOutcome get() = CancelOutcome.NOT_ARRANGED
    }

    /** 何も変えず、記録から決まる結果を返す(出荷済みなら取り消さない)。 */
    public data class Replay(
        override val outcome: CancelOutcome,
    ) : CancelDecision
}

/**
 * 出荷の規則(模擬。ADR-0029 §7)。純粋な関数で、永続化は application と adapters が行う。
 *
 * @property supportedCountries 出荷できる国(既定は JP だけ)
 */
public class ShippingRules(
    private val supportedCountries: Set<String> = DEFAULT_COUNTRIES,
) {
    /** 手配の指示の判定。初めてなら、出荷できる国なら直ちに出荷する。同じ Saga の記録があれば返し直す(印なら `ALREADY_CANCELLED`)。 */
    public fun arrange(
        existing: Shipment?,
        sagaId: String,
        orderId: String,
        destination: Destination,
        stamp: () -> ShipmentStamp,
    ): ArrangeDecision {
        if (existing != null) return ArrangeDecision.Replay(replyOf(existing))
        return if (destination.countryCode in supportedCountries) {
            val (id, at) = stamp()
            ArrangeDecision.Record(
                Shipment(sagaId, orderId, ShipmentStatus.SHIPPED, shipmentId = id, shippedAt = at, destination = destination),
                ArrangeReply.Shipped(id, at),
            )
        } else {
            ArrangeDecision.Record(
                Shipment(
                    sagaId,
                    orderId,
                    ShipmentStatus.REJECTED,
                    rejection = RejectionReason.UNSUPPORTED_DESTINATION,
                    destination = destination,
                ),
                ArrangeReply.Rejected(RejectionReason.UNSUPPORTED_DESTINATION),
            )
        }
    }

    /** 取消の指示の判定。出荷済みなら取り消さない(`ALREADY_SHIPPED`。Saga は補償をやめて完了に進む。ADR-0029 §3)。 */
    public fun cancel(
        existing: Shipment?,
        sagaId: String,
        orderId: String,
    ): CancelDecision =
        when (existing?.status) {
            null -> CancelDecision.MarkCancelledBeforeArrangement(Shipment(sagaId, orderId, ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT))
            ShipmentStatus.SHIPPED -> CancelDecision.Replay(CancelOutcome.ALREADY_SHIPPED)
            ShipmentStatus.REJECTED, ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT -> CancelDecision.Replay(CancelOutcome.NOT_ARRANGED)
        }

    private fun replyOf(existing: Shipment): ArrangeReply =
        when (existing.status) {
            ShipmentStatus.SHIPPED -> ArrangeReply.Shipped(checkNotNull(existing.shipmentId), checkNotNull(existing.shippedAt))
            ShipmentStatus.REJECTED -> ArrangeReply.Rejected(checkNotNull(existing.rejection))
            ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT -> ArrangeReply.Rejected(RejectionReason.ALREADY_CANCELLED)
        }

    public companion object {
        /** 模擬の既定(ADR-0029 §7)。 */
        public val DEFAULT_COUNTRIES: Set<String> = setOf("JP")
    }
}

/** 識別子を検査する(値はエラーに入れない)。 */
public fun validateIdentifiers(vararg fields: Pair<String, String>): Result<Unit, ValidationError> {
    val violations =
        fields.filter { (_, value) -> value.isBlank() || value.length > MAX_IDENTIFIER_LENGTH }.map { (name, _) ->
            FieldViolation(name, "1〜$MAX_IDENTIFIER_LENGTH 文字にしてください")
        }
    return if (violations.isEmpty()) ok(Unit) else err(ValidationError(violations))
}

/**
 * 終わった出荷の記録(印を含む)の保持期間(ADR-0029 §5)。既定は 30 日で、[MINIMUM] より短くはできない。
 * 遅れて届いた手配の指示を拒否するため、印はコマンドのトピック(7 日)と DLQ(7 日)の保持期間の合計([MINIMUM])より長く残す。
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
