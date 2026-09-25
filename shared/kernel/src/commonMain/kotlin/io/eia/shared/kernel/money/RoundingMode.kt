package io.eia.shared.kernel.money

/**
 * 丸め方。率を掛ける演算([Money.times])では必ず明示し、既定値は持たない(ADR-0011)。
 * 意味は `java.math.RoundingMode` と同じ。
 */
public enum class RoundingMode {
    /** 0 から離れる方向へ切り上げる。 */
    UP,

    /** 0 に近づく方向へ切り捨てる。 */
    DOWN,

    /** 正の無限大方向へ丸める。 */
    CEILING,

    /** 負の無限大方向へ丸める。 */
    FLOOR,

    /** 四捨五入(ちょうど半分は 0 から離れる方向)。 */
    HALF_UP,

    /** 五捨六入(ちょうど半分は 0 に近づく方向)。 */
    HALF_DOWN,

    /** 偶数丸め(ちょうど半分は偶数側)。銀行家の丸め。 */
    HALF_EVEN,
}

/** `numerator / denominator` を [mode] で整数に丸める。[denominator] は正。 */
internal fun divideRounded(
    numerator: Long,
    denominator: Long,
    mode: RoundingMode,
): Long {
    require(denominator > 0) { "denominator は正の値です: $denominator" }
    val quotient = numerator / denominator
    val remainder = numerator % denominator
    if (remainder == 0L) return quotient
    val positive = numerator > 0
    val absRemainder = if (remainder < 0) -remainder else remainder
    // |remainder| と denominator - |remainder| の比較で、端数が半分より大きいかを判定する(2 倍しないのでオーバーフローしない)
    val half = absRemainder.compareTo(denominator - absRemainder)
    val awayFromZero =
        when (mode) {
            RoundingMode.UP -> true
            RoundingMode.DOWN -> false
            RoundingMode.CEILING -> positive
            RoundingMode.FLOOR -> !positive
            RoundingMode.HALF_UP -> half >= 0
            RoundingMode.HALF_DOWN -> half > 0
            RoundingMode.HALF_EVEN -> half > 0 || (half == 0 && quotient % 2 != 0L)
        }
    return if (!awayFromZero) {
        quotient
    } else if (positive) {
        quotient + 1
    } else {
        quotient - 1
    }
}
