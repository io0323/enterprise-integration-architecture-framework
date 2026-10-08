package io.eia.legacyorderacl.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <T> Result<T, TranslationError>.failure(): TranslationError =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

/** 現地時刻を UTC のエポックとみなしたマイクロ秒(Debezium の MicroTimestamp)。 */
private fun localMicros(iso: String): Long {
    val asUtc = Instant.parse("${iso}Z")
    return asUtc.epochSeconds * 1_000_000 + asUtc.nanosecondsOfSecond / 1_000
}

private val ROW =
    LegacyOrderRow(
        orderNumber = "J000000001",
        statusCode = "1",
        customerName = "山田商事株式会社".padEnd(40),
        customerCode = "C0000101",
        amount = "1200.00",
        orderedAtLocalMicros = localMicros("2026-10-08T09:00:00.123456"),
        updatedAtLocalMicros = LegacyOrderTranslation.UNSET_LOCAL_MICROS,
    )

class LegacyOrderTranslationSpec :
    FunSpec({
        test("正常な行を変換する: 末尾の空白を除き、状態をコード表で引き、円の金額にし、JST を UTC にする") {
            val order = LegacyOrderTranslation.translate(ROW).ok()
            order shouldBe
                LegacyOrder(
                    orderNumber = "J000000001",
                    customerCode = "C0000101",
                    customerName = "山田商事株式会社",
                    status = LegacyOrderStatus.ACCEPTED,
                    totalAmount = Money.ofMinor(1200, Currency.JPY),
                    orderedAt = Instant.parse("2026-10-08T00:00:00.123456Z"),
                    legacyUpdatedAt = null,
                )
        }

        context("時刻(JST の現地時刻 → UTC)") {
            test("日付の境界をまたぐ: 2026-01-01 08:59:59.999999 JST は 2025-12-31T23:59:59.999999Z") {
                LegacyOrderTranslation.localMicrosToInstant(localMicros("2026-01-01T08:59:59.999999"), "col_07").ok() shouldBe
                    Instant.parse("2025-12-31T23:59:59.999999Z")
            }

            test("マイクロ秒未満は過去方向に切り捨てる(ADR-0012 §3)") {
                LegacyOrderTranslation.localToInstant(LocalDateTime(2026, 10, 8, 9, 0, 0, 123_456_789), "col_07").ok() shouldBe
                    Instant.parse("2026-10-08T00:00:00.123456Z")
            }

            test("エポック以前の時刻も変換できる") {
                LegacyOrderTranslation.localMicrosToInstant(localMicros("1969-12-31T23:59:59.999999"), "col_07").ok() shouldBe
                    Instant.parse("1969-12-31T14:59:59.999999Z")
            }

            test("最終更新日時の番兵値 9999-12-31 00:00:00 は null(未設定)、それ以外は UTC") {
                LegacyOrderTranslation
                    .translate(ROW)
                    .ok()
                    .legacyUpdatedAt
                    .shouldBeNull()
                LegacyOrderTranslation
                    .translate(ROW.copy(updatedAtLocalMicros = localMicros("2026-10-08T10:30:00")))
                    .ok()
                    .legacyUpdatedAt shouldBe Instant.parse("2026-10-08T01:30:00Z")
            }
        }

        context("固定長の文字列") {
            test("末尾の半角の空白だけを除き、全角の空白と先頭の空白は残す") {
                LegacyOrderTranslation.text(" ｶ)ｽｽﾞｷ　 ", "col_04").ok() shouldBe " ｶ)ｽｽﾞｷ　"
            }

            test("空白だけなら値がない(MISSING_VALUE)") {
                LegacyOrderTranslation.translate(ROW.copy(customerCode = "        ")).failure().failure shouldBe
                    TranslationFailure.MISSING_VALUE
            }

            test("置換文字や制御文字を含めば文字化け(MALFORMED_TEXT)。エラーに値を入れない") {
                val cases = listOf("ｶ)ﾃｽﾄ��商事", "山田\u0000商事", "山田\u0085商事", "山田\u007F")
                cases.forEach { name ->
                    val error = LegacyOrderTranslation.translate(ROW.copy(customerName = name)).failure()
                    error.failure shouldBe TranslationFailure.MALFORMED_TEXT
                    error.column shouldBe "col_04"
                    error.message shouldNotContain "山田"
                }
            }
        }

        context("状態区分(コード値)") {
            test("対応表: 1 受付 / 2 引当済 / 3 出荷済 / 9 取消") {
                mapOf(
                    "1" to LegacyOrderStatus.ACCEPTED,
                    "2" to LegacyOrderStatus.ALLOCATED,
                    "3" to LegacyOrderStatus.SHIPPED,
                    "9" to LegacyOrderStatus.CANCELLED,
                ).forEach { (code, status) -> LegacyOrderTranslation.status(code).ok() shouldBe status }
            }

            test("対応表にないコードは UNKNOWN_STATUS_CODE") {
                listOf("7", "0", " ", "").forEach { code ->
                    LegacyOrderTranslation.translate(ROW.copy(statusCode = code)).failure().failure shouldBe
                        TranslationFailure.UNKNOWN_STATUS_CODE
                }
            }
        }

        context("金額(通貨のない小数 → 円)") {
            test("小数部が 0 なら円にする。通貨は JPY と明示する") {
                val cases = mapOf("1200.00" to 1200L, "0.00" to 0L, "-0.00" to 0L, "9999999999.00" to 9_999_999_999L, "000123.0" to 123L)
                cases.forEach { (raw, yen) ->
                    LegacyOrderTranslation.amount(raw).ok() shouldBe Money.ofMinor(yen, Currency.JPY)
                }
            }

            test("小数部が 0 でなければ丸めずに AMOUNT_HAS_FRACTION") {
                listOf("1234.50", "1.01", "0.10").forEach { raw ->
                    LegacyOrderTranslation.amount(raw).failure().failure shouldBe TranslationFailure.AMOUNT_HAS_FRACTION
                }
            }

            test("負・上限超え(列の桁には収まる値・Long を超える値)は AMOUNT_OUT_OF_RANGE") {
                listOf("-1.00", "10000000000.00", "99999999999.00", "99999999999999999999999.00").forEach { raw ->
                    LegacyOrderTranslation.amount(raw).failure().failure shouldBe TranslationFailure.AMOUNT_OUT_OF_RANGE
                }
            }

            test("数値でない文字列は UNDECODABLE") {
                listOf("", "1,200.00", "1e3", "abc").forEach { raw ->
                    LegacyOrderTranslation.amount(raw).failure().failure shouldBe TranslationFailure.UNDECODABLE
                }
            }
        }

        test("エラーのコードは DLQ の reason と対応する(snake_case)") {
            TranslationError(TranslationFailure.UNKNOWN_STATUS_CODE, "col_03", "x").code shouldBe "unknown_status_code"
        }
    })
