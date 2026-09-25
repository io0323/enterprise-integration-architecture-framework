package io.eia.shared.kernel.money

import io.eia.shared.kernel.DomainError

/** [Money] の演算エラー。入力や計算の誤りで、リトライしても解決しない。 */
public sealed interface MoneyError : DomainError.NonRetryable {
    public data class CurrencyMismatch(
        public val expected: Currency,
        public val actual: Currency,
    ) : MoneyError {
        override val code: String get() = "currency_mismatch"
        override val message: String get() = "通貨が一致しません: $expected と $actual"
    }

    /** 最小通貨単位の Long の範囲を超えた(ADR-0011)。 */
    public data class Overflow(
        public val operation: String,
    ) : MoneyError {
        override val code: String get() = "money_overflow"
        override val message: String get() = "金額が表現できる範囲を超えました: $operation"
    }

    public data class InvalidAllocation(
        override val message: String,
    ) : MoneyError {
        override val code: String get() = "invalid_allocation"
    }
}
