package io.eia.legacyorderacl.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.ok
import io.eia.shared.kernel.truncatedToMicros
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * レガシーの受注の 1 行を [LegacyOrder] に変換する(Anti-Corruption Layer の規則。ADR-0026 §8)。
 * 合わない値は [TranslationError] にする(黙って捨てない・丸めない)。
 */
public object LegacyOrderTranslation {
    /** レガシーのサーバのタイムゾーン(タイムゾーンのない時刻はこの現地時刻)。 */
    public val LEGACY_TIME_ZONE: TimeZone = TimeZone.of("Asia/Tokyo")

    /** レガシーは通貨の列を持たない。円と明示する(暗黙にしない。ADR-0011)。 */
    public val LEGACY_CURRENCY: Currency = Currency.JPY

    /** 1 件の受注の金額の上限(円。契約 LegacyOrderChanged の totalAmount)。 */
    public const val MAX_AMOUNT_YEN: Long = 9_999_999_999L

    /** レガシーの「未設定」の番兵値 9999-12-31 00:00:00 の、現地時刻を UTC のエポックとみなしたマイクロ秒。 */
    public const val UNSET_LOCAL_MICROS: Long = 253_402_214_400_000_000L

    private const val ORDER_NUMBER = "col_02"
    private const val STATUS = "col_03"
    private const val CUSTOMER_NAME = "col_04"
    private const val CUSTOMER_CODE = "col_05"
    private const val AMOUNT = "col_06"
    private const val ORDERED_AT = "col_07"
    private const val UPDATED_AT = "col_08"
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L
    private val DECIMAL = Regex("""^(-?)(\d+)(?:\.(\d+))?$""")

    public fun translate(row: LegacyOrderRow): Result<LegacyOrder, TranslationError> =
        orderNumber(row.orderNumber).flatMap { number ->
            text(row.customerCode, CUSTOMER_CODE).flatMap { customerCode ->
                text(row.customerName, CUSTOMER_NAME).flatMap { customerName ->
                    status(row.statusCode).flatMap { status ->
                        amount(row.amount).flatMap { amount ->
                            localMicrosToInstant(row.orderedAtLocalMicros, ORDERED_AT).flatMap { orderedAt ->
                                updatedAt(row.updatedAtLocalMicros).map { updatedAt ->
                                    LegacyOrder(number, customerCode, customerName, status, amount, orderedAt, updatedAt)
                                }
                            }
                        }
                    }
                }
            }
        }

    /** 注文番号(出力のキー)。削除の変更からも使う。 */
    public fun orderNumber(raw: String): Result<String, TranslationError> = text(raw, ORDER_NUMBER)

    /**
     * 固定長の文字列(CHAR)の末尾の半角の空白(U+0020)だけを除く。全角の空白と先頭の空白は残す。
     * 置換文字(U+FFFD)か制御文字を含めば文字化け、除いた結果が空なら値がないとみなす。
     */
    public fun text(
        raw: String,
        column: String,
    ): Result<String, TranslationError> {
        val trimmed = raw.trimEnd(' ')
        return when {
            trimmed.any { it == REPLACEMENT_CHARACTER || it.isControlCharacter() } -> {
                err(TranslationError(TranslationFailure.MALFORMED_TEXT, column, "置換文字(U+FFFD)か制御文字を含む"))
            }

            trimmed.isEmpty() -> {
                err(TranslationError(TranslationFailure.MISSING_VALUE, column, "末尾の空白を除くと空"))
            }

            else -> {
                ok(trimmed)
            }
        }
    }

    public fun status(code: String): Result<LegacyOrderStatus, TranslationError> =
        LegacyOrderStatus.ofLegacyCode(code)?.let { ok(it) }
            ?: err(TranslationError(TranslationFailure.UNKNOWN_STATUS_CODE, STATUS, "状態区分の対応表にないコード"))

    /** 小数の文字列(NUMERIC(13,2))を円にする。小数部が 0 でなければ誤り(丸めない)。範囲は 0 以上 [MAX_AMOUNT_YEN] 以下。 */
    public fun amount(raw: String): Result<Money, TranslationError> {
        val match =
            DECIMAL.matchEntire(raw)
                ?: return err(TranslationError(TranslationFailure.UNDECODABLE, AMOUNT, "小数の文字列ではない"))
        val (sign, integer, fraction) = match.destructured
        val significant = integer.trimStart('0')
        return when {
            fraction.any { it != '0' } -> {
                err(TranslationError(TranslationFailure.AMOUNT_HAS_FRACTION, AMOUNT, "円(小数桁 0)に 0 でない小数部がある"))
            }

            sign == "-" && significant.isNotEmpty() -> {
                err(TranslationError(TranslationFailure.AMOUNT_OUT_OF_RANGE, AMOUNT, "負の金額"))
            }

            significant.length > MAX_AMOUNT_YEN.toString().length || (significant.toLongOrNull() ?: 0L) > MAX_AMOUNT_YEN -> {
                err(TranslationError(TranslationFailure.AMOUNT_OUT_OF_RANGE, AMOUNT, "上限($MAX_AMOUNT_YEN 円)を超える"))
            }

            else -> {
                ok(Money.ofMinor(significant.toLongOrNull() ?: 0L, LEGACY_CURRENCY))
            }
        }
    }

    /**
     * タイムゾーンのない現地時刻(JST)を、UTC の [Instant] にする。[localMicros] は現地時刻を UTC のエポックとみなしたマイクロ秒
     * (Debezium の MicroTimestamp)。重複する現地時刻は早い方、存在しない現地時刻は誤り(JST には夏時間がないので起きない)。
     */
    public fun localMicrosToInstant(
        localMicros: Long,
        column: String,
    ): Result<Instant, TranslationError> {
        val local =
            Instant
                .fromEpochSeconds(localMicros.floorDiv(MICROS_PER_SECOND), localMicros.mod(MICROS_PER_SECOND) * NANOS_PER_MICRO)
                .toLocalDateTime(TimeZone.UTC)
        return localToInstant(local, column)
    }

    /** [local](JST の現地時刻)を UTC の [Instant] にし、マイクロ秒未満を過去方向に切り捨てる(ADR-0012 §3)。 */
    public fun localToInstant(
        local: LocalDateTime,
        column: String,
    ): Result<Instant, TranslationError> {
        val instant = local.toInstant(LEGACY_TIME_ZONE)
        // 存在しない現地時刻(夏時間の開始の空白)は、変換で別の時刻にずれる。戻して一致しなければ誤り
        return if (instant.toLocalDateTime(LEGACY_TIME_ZONE) == local) {
            ok(instant.truncatedToMicros())
        } else {
            err(TranslationError(TranslationFailure.INVALID_LOCAL_TIME, column, "${LEGACY_TIME_ZONE.id} に存在しない現地時刻"))
        }
    }

    private fun updatedAt(localMicros: Long): Result<Instant?, TranslationError> =
        if (localMicros == UNSET_LOCAL_MICROS) ok(null) else localMicrosToInstant(localMicros, UPDATED_AT)

    private const val REPLACEMENT_CHARACTER = '�'

    /** C0(U+0000〜U+001F・U+007F)と C1(U+0080〜U+009F)の制御文字。 */
    private fun Char.isControlCharacter(): Boolean = this < ' ' || this in '\u007F'..'\u009F'
}
