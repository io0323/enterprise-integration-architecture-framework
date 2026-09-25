package io.eia.shared.kernel.money

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 通貨付きの金額。論理モデルとしての金額で、最小通貨単位(JPY なら円、USD ならセント)の Long で保持する(ADR-0011)。
 *
 * - 通貨の異なる金額どうしの演算は [MoneyError.CurrencyMismatch]、Long の範囲を超える演算は [MoneyError.Overflow] を返す。
 * - 率を掛ける演算は丸め方を必ず引数で受け取る。暗黙の丸めはしない。
 * - JSON・Avro などでの物理表現はフォーマットごとに別途定める(JSON は `shared/canonical-model` の MoneySerializer)。
 */
public class Money private constructor(
    public val minorUnits: Long,
    public val currency: Currency,
) {
    public val isZero: Boolean get() = minorUnits == 0L
    public val isNegative: Boolean get() = minorUnits < 0L

    public operator fun plus(other: Money): Result<Money, MoneyError> =
        sameCurrency(other) { a, b -> a.plusExactOrNull(b) ?: return err(MoneyError.Overflow("$this + $other")) }

    public operator fun minus(other: Money): Result<Money, MoneyError> =
        sameCurrency(other) { a, b ->
            // -Long.MIN_VALUE は表現できないため、減算は符号反転ではなく差の検査で行う
            val difference = a - b
            if ((a xor b) and (a xor difference) < 0) return err(MoneyError.Overflow("$this - $other"))
            difference
        }

    /** 数量倍(単価 × 数量)。 */
    public operator fun times(quantity: Long): Result<Money, MoneyError> =
        minorUnits.timesExactOrNull(quantity)?.let { ok(Money(it, currency)) } ?: err(MoneyError.Overflow("$this * $quantity"))

    /** 率を掛ける(税額・割引額)。結果の最小通貨単位未満は [rounding] で丸める。 */
    public fun times(
        rate: Rate,
        rounding: RoundingMode,
    ): Result<Money, MoneyError> {
        val scaled = minorUnits.timesExactOrNull(rate.numerator) ?: return err(MoneyError.Overflow("$this * $rate"))
        return ok(Money(divideRounded(scaled, rate.denominator, rounding), currency))
    }

    public operator fun unaryMinus(): Result<Money, MoneyError> =
        if (minorUnits == Long.MIN_VALUE) err(MoneyError.Overflow("-$this")) else ok(Money(-minorUnits, currency))

    /** 小数点付きの 10 進表記(例: USD の 1234 → "12.34"、JPY の 1234 → "1234")。桁数は常に通貨の小数桁数。 */
    public fun toDecimalString(): String {
        val digits = currency.minorUnitDigits
        val magnitude = minorUnits.toString().removePrefix("-").padStart(digits + 1, '0')
        val sign = if (minorUnits < 0) "-" else ""
        if (digits == 0) return sign + magnitude
        return sign + magnitude.dropLast(digits) + "." + magnitude.takeLast(digits)
    }

    override fun equals(other: Any?): Boolean = other is Money && minorUnits == other.minorUnits && currency == other.currency

    override fun hashCode(): Int = 31 * minorUnits.hashCode() + currency.hashCode()

    override fun toString(): String = "${toDecimalString()} $currency"

    private inline fun sameCurrency(
        other: Money,
        operation: (Long, Long) -> Long,
    ): Result<Money, MoneyError> =
        if (currency != other.currency) {
            err(MoneyError.CurrencyMismatch(currency, other.currency))
        } else {
            ok(Money(operation(minorUnits, other.minorUnits), currency))
        }

    public companion object {
        private val DECIMAL = Regex("""^(-?)(\d+)(?:\.(\d+))?$""")

        public fun ofMinor(
            minorUnits: Long,
            currency: Currency,
        ): Money = Money(minorUnits, currency)

        public fun zero(currency: Currency): Money = Money(0, currency)

        /**
         * 10 進表記の金額を読む(例: "1234.50")。指数表記・桁区切り・前後の空白は受け付けない。
         * 小数部が通貨の小数桁数を超える値(JPY の "100.5"、USD の "1.005")は丸めずにエラーにする。
         */
        public fun parse(
            amount: String,
            currency: Currency,
        ): Result<Money, ValidationError> {
            val match = DECIMAL.matchEntire(amount) ?: return err(ValidationError.of("amount", "10 進表記の金額ではありません"))
            val (sign, integerPart, fractionPart) = match.destructured
            val digits = currency.minorUnitDigits
            val magnitude = (integerPart + fractionPart.padEnd(digits, '0')).trimStart('0').ifEmpty { "0" }.toLongOrNull()
            return when {
                fractionPart.length > digits -> {
                    err(ValidationError.of("amount", "小数部が ${currency.code} の小数桁数($digits)を超えています"))
                }

                magnitude == null -> {
                    err(ValidationError.of("amount", "金額が表現できる範囲を超えています"))
                }

                else -> {
                    ok(Money(if (sign == "-") -magnitude else magnitude, currency))
                }
            }
        }

        /** [amounts] の合計。空なら [currency] の 0。 */
        public fun sum(
            currency: Currency,
            amounts: Iterable<Money>,
        ): Result<Money, MoneyError> {
            var total = zero(currency)
            for (amount in amounts) {
                when (val next = total + amount) {
                    is Result.Ok -> total = next.value
                    is Result.Err -> return next
                }
            }
            return ok(total)
        }
    }
}
