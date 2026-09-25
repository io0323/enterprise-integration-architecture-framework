package io.eia.shared.canonical.common

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money

// 金額の検証で共通に使うチェック。違反メッセージには金額の値を含めない(ログへのペイロード出力を避ける)。

internal fun Violations.checkCurrency(
    field: String,
    amount: Money,
    expected: Currency,
) {
    check(amount.currency == expected, field) { "通貨が ${expected.code} ではありません(${amount.currency.code})" }
}

internal fun Violations.checkNotNegative(
    field: String,
    amount: Money,
) {
    check(!amount.isNegative, field) { "0 以上です" }
}

/** [actual] が [expected] の計算結果と一致するか。計算自体の失敗(通貨の不一致・オーバーフロー)も違反にする。 */
internal fun Violations.checkEquals(
    field: String,
    actual: Money,
    expected: Result<Money, DomainError>,
    description: String,
) {
    when (expected) {
        is Result.Ok -> check(actual == expected.value, field) { "$description と一致しません" }
        is Result.Err -> check(false, field) { "$description を計算できません(${expected.error.code})" }
    }
}
