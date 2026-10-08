package io.eia.legacyorderacl.domain

import io.eia.shared.kernel.DomainError

/** 変換できない理由(DLQ の `eiaf.dlq.reason`。ADR-0026 §7)。 */
public enum class TranslationFailure {
    UNKNOWN_STATUS_CODE,
    AMOUNT_OUT_OF_RANGE,
    AMOUNT_HAS_FRACTION,
    MALFORMED_TEXT,
    INVALID_LOCAL_TIME,
    MISSING_VALUE,

    /** 形式が想定と違い、値として読めない(Avro として読めない・未知の op・数値でない金額など) */
    UNDECODABLE,
}

/**
 * レガシーの値が変換の規則に合わない(決定的な誤りなので、リトライせずに DLQ に送る。ADR-0026 §7)。
 * [message] には列の名前と破った規則だけを入れ、**値は入れない**(機密区分・ペイロードのログの禁止)。
 *
 * @property column レガシーの列(例 `col_03`)。列に当たらなければ `-`
 */
public data class TranslationError(
    public val failure: TranslationFailure,
    public val column: String,
    public val rule: String,
) : DomainError.NonRetryable {
    override val code: String get() = failure.name.lowercase()
    override val message: String get() = "$column: $rule"
}
