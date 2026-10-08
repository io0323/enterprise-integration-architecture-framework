package io.eia.legacyorderacl.domain

import kotlin.time.Instant

/**
 * 照合(reconcile。ADR-0027)で比べる、注文の状態の正規の文字列。レガシーの行を変換した [LegacyOrder] と、
 * 整形済みのトピックの値から作った [LegacyOrder] を、同じ形で比べるために使う。ハッシュ(SHA-256)は adapters が計算する。
 *
 * - 項目の順序と書式を固定する。区切りの `|` と、値の中の `|`・`\` は `\` でエスケープする(区切りとの取り違えを防ぐ)。
 * - 時刻は UTC のエポックからのマイクロ秒(契約の timestamp-micros と同じ精度)。`legacyUpdatedAt` がなければ `-`。
 * - 変更の位置(`source`)は含めない(同じ状態でも、送り直しで位置が変わるため)。
 */
public object LegacyOrderFingerprint {
    private const val SEPARATOR = "|"
    private const val NONE = "-"
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000

    public fun canonical(order: LegacyOrder): String =
        listOf(
            escape(order.orderNumber),
            escape(order.customerCode),
            escape(order.customerName),
            order.status.name,
            order.totalAmount.minorUnits.toString(),
            order.totalAmount.currency.code,
            micros(order.orderedAt).toString(),
            order.legacyUpdatedAt?.let { micros(it).toString() } ?: NONE,
        ).joinToString(SEPARATOR)

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace(SEPARATOR, "\\" + SEPARATOR)

    private fun micros(instant: Instant): Long = instant.epochSeconds * MICROS_PER_SECOND + instant.nanosecondsOfSecond / NANOS_PER_MICRO
}
