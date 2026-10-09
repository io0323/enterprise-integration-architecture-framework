package io.eia.inventory.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/** 識別子(Saga ID・注文 ID・SKU)の長さの上限(DB の列と同じ)。 */
public const val MAX_IDENTIFIER_LENGTH: Int = 64

/**
 * 引当の 1 明細。
 *
 * @property quantity 1 以上
 */
public data class StockLine(
    public val lineNumber: Int,
    public val sku: String,
    public val quantity: Long,
) {
    public companion object {
        /** 契約の値を検査して作る(値はエラーに入れない)。 */
        public fun of(
            lineNumber: Int,
            sku: String,
            quantity: Long,
        ): Result<StockLine, ValidationError> {
            val violations =
                buildList {
                    if (lineNumber < 1) add(FieldViolation("lines.lineNumber", "1 以上にしてください"))
                    if (!isIdentifier(sku)) add(FieldViolation("lines.sku", "1〜$MAX_IDENTIFIER_LENGTH 文字にしてください"))
                    if (quantity < 1) add(FieldViolation("lines.quantity", "1 以上にしてください"))
                }
            return if (violations.isEmpty()) ok(StockLine(lineNumber, sku, quantity)) else err(ValidationError(violations))
        }
    }
}

/** SKU ごとの在庫。引当できる数は [onHand] - [reserved]。 */
public data class StockLevel(
    public val sku: String,
    public val onHand: Long,
    public val reserved: Long,
) {
    public val available: Long get() = onHand - reserved
}

/** 引当の記録の状態(Saga ID ごとに 1 行。ADR-0029 §5)。 */
public enum class ReservationStatus {
    /** 引き当てている。 */
    RESERVED,

    /** 引き当てなかった(在庫不足など。何も引き当てていない)。 */
    REJECTED,

    /** 引き当てた後に解放した(補償)。 */
    RELEASED,

    /**
     * 「取消済み」の印: 引当より先に解放の指示が届いた。後から届いた引当の指示を拒否する(ADR-0029 §5)。
     * 何も引き当てていない。
     */
    RELEASED_BEFORE_RESERVATION,
    ;

    /** 終わった状態(保持期間を過ぎたら消してよい)。有効な引当([RESERVED])だけが終わっていない。 */
    public val isSettled: Boolean get() = this != RESERVED
}

/** 引当を拒否した理由(契約の `StockRejectionReason`)。 */
public enum class RejectionReason {
    INSUFFICIENT_STOCK,
    UNKNOWN_SKU,
    ALREADY_RELEASED,
}

/** 解放の結果(契約の `StockReleaseOutcome`)。 */
public enum class ReleaseOutcome {
    RELEASED,
    NOT_RESERVED,
}

/**
 * 引当の記録(Saga ID が業務キー。ADR-0029 §5)。
 *
 * @property rejection [ReservationStatus.REJECTED] のときの理由
 * @property lines 引当の明細(解放で在庫を戻すために持つ)。印と拒否では空
 */
public data class Reservation(
    public val sagaId: String,
    public val orderId: String,
    public val status: ReservationStatus,
    public val rejection: RejectionReason? = null,
    public val lines: List<StockLine> = emptyList(),
)

internal fun isIdentifier(value: String): Boolean = value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH

/** Saga ID と注文 ID を検査する(値はエラーに入れない)。 */
public fun validateIds(
    sagaId: String,
    orderId: String,
): Result<Unit, ValidationError> {
    val violations =
        buildList {
            if (!isIdentifier(sagaId)) add(FieldViolation("sagaId", "1〜$MAX_IDENTIFIER_LENGTH 文字にしてください"))
            if (!isIdentifier(orderId)) add(FieldViolation("orderId", "1〜$MAX_IDENTIFIER_LENGTH 文字にしてください"))
        }
    return if (violations.isEmpty()) ok(Unit) else err(ValidationError(violations))
}
