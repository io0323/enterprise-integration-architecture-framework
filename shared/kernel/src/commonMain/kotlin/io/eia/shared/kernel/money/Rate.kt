package io.eia.shared.kernel.money

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 税率・割引率などの率。浮動小数点を使わず、既約分数 `numerator / denominator` で表す(ADR-0011)。
 *
 * 10% は `Rate.basisPoints(1000)` または `Rate.of(1, 10)`。どちらも同じ値として等しい。
 */
public class Rate private constructor(
    public val numerator: Long,
    public val denominator: Long,
) {
    override fun equals(other: Any?): Boolean = other is Rate && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    public companion object {
        /** basis points の分母(1 bp = 0.01%)。 */
        public const val BASIS_POINTS_PER_UNIT: Long = 10_000

        public val ZERO: Rate = Rate(0, 1)
        public val ONE: Rate = Rate(1, 1)

        /** 率を分数で作る。分子は 0 以上、分母は正。 */
        public fun of(
            numerator: Long,
            denominator: Long,
        ): Result<Rate, ValidationError> =
            when {
                numerator < 0 -> err(ValidationError.of("rate", "率は 0 以上です: $numerator/$denominator"))
                denominator <= 0 -> err(ValidationError.of("rate", "分母は正の値です: $numerator/$denominator"))
                else -> ok(reduced(numerator, denominator))
            }

        /** 率を basis points(1 万分の 1 単位)で作る。例: 10% = 1000。 */
        public fun basisPoints(basisPoints: Long): Result<Rate, ValidationError> = of(basisPoints, BASIS_POINTS_PER_UNIT)

        private fun reduced(
            numerator: Long,
            denominator: Long,
        ): Rate {
            val divisor = gcd(numerator, denominator)
            return Rate(numerator / divisor, denominator / divisor)
        }

        private tailrec fun gcd(
            a: Long,
            b: Long,
        ): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
