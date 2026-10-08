package io.eia.legacyorderacl.domain

import io.eia.shared.kernel.money.Money
import kotlin.time.Instant

/**
 * レガシーの受注(注文のヘッダ)を、レガシーの表現から切り離した値(ADR-0026 §8)。
 *
 * @property orderNumber 末尾の空白を除いた注文番号(出力のキー)
 * @property legacyUpdatedAt レガシーの「未設定」(9999-12-31 00:00:00)は null
 */
public data class LegacyOrder(
    public val orderNumber: String,
    public val customerCode: String,
    public val customerName: String,
    public val status: LegacyOrderStatus,
    public val totalAmount: Money,
    public val orderedAt: Instant,
    public val legacyUpdatedAt: Instant?,
)

/** 注文の状態。レガシーの状態区分(コード値)の対応表を持つ(ADR-0026 §8。変えるときは ADR を改訂する)。 */
public enum class LegacyOrderStatus(
    public val legacyCode: String,
) {
    /** '1' 受付 */
    ACCEPTED("1"),

    /** '2' 引当済 */
    ALLOCATED("2"),

    /** '3' 出荷済 */
    SHIPPED("3"),

    /** '9' 取消 */
    CANCELLED("9"),
    ;

    public companion object {
        public fun ofLegacyCode(code: String): LegacyOrderStatus? = entries.firstOrNull { it.legacyCode == code }
    }
}

/**
 * レガシーの受注表(t_juchu)の 1 行。値はレガシーの形式のまま(固定長の文字列は空白で埋まっている。金額は小数の文字列。
 * 時刻はタイムゾーンのない現地時刻を UTC のエポックとみなしたマイクロ秒)。
 */
public data class LegacyOrderRow(
    public val orderNumber: String,
    public val statusCode: String,
    public val customerName: String,
    public val customerCode: String,
    public val amount: String,
    public val orderedAtLocalMicros: Long,
    public val updatedAtLocalMicros: Long,
)
