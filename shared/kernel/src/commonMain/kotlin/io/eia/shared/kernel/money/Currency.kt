package io.eia.shared.kernel.money

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * ISO 4217 の通貨。コードと小数桁数(最小通貨単位の桁数)を持つ値オブジェクト(ADR-0011)。
 *
 * よく使う通貨は定数で用意し、それ以外は [of] で生成する。通貨の追加で kernel を変更する必要はない。
 * 2 つのプロパティを持つため `@JvmInline value class` にはできず、通常のクラスで値の等価性を実装する。
 */
public class Currency private constructor(
    public val code: String,
    public val minorUnitDigits: Int,
) {
    override fun equals(other: Any?): Boolean = other is Currency && code == other.code && minorUnitDigits == other.minorUnitDigits

    override fun hashCode(): Int = 31 * code.hashCode() + minorUnitDigits

    override fun toString(): String = code

    public companion object {
        /** ISO 4217 の小数桁数の最大値(CLF・UYW が 4)。 */
        public const val MAX_MINOR_UNIT_DIGITS: Int = 4
        private val CODE = Regex("^[A-Z]{3}$")

        public val JPY: Currency = Currency("JPY", 0)
        public val USD: Currency = Currency("USD", 2)
        public val EUR: Currency = Currency("EUR", 2)
        public val GBP: Currency = Currency("GBP", 2)
        public val CNY: Currency = Currency("CNY", 2)
        public val KRW: Currency = Currency("KRW", 0)

        /** 定数で用意している通貨。 */
        public val COMMON: List<Currency> = listOf(JPY, USD, EUR, GBP, CNY, KRW)

        public fun of(
            code: String,
            minorUnitDigits: Int,
        ): Result<Currency, ValidationError> =
            when {
                !CODE.matches(code) -> err(ValidationError.of("currency", "ISO 4217 の英大文字 3 桁のコードです: '$code'"))
                minorUnitDigits !in 0..MAX_MINOR_UNIT_DIGITS -> err(ValidationError.of("currency", "小数桁数は 0〜4 です: $minorUnitDigits"))
                else -> ok(Currency(code, minorUnitDigits))
            }
    }
}

/**
 * 通貨コードから [Currency] を引く。JSON などコードだけを運ぶ表現を復元するときに使う。
 * 扱う通貨を増やすときは、このインターフェースの実装を差し替える(kernel は変更しない)。
 */
public fun interface CurrencyResolver {
    public fun resolve(code: String): Currency?

    public companion object {
        /** [Currency.COMMON] の通貨だけを解決する既定の実装。 */
        public val COMMON: CurrencyResolver = of(Currency.COMMON)

        public fun of(currencies: Iterable<Currency>): CurrencyResolver {
            val byCode = currencies.associateBy { it.code }
            return CurrencyResolver { byCode[it] }
        }
    }
}
