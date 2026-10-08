@file:Suppress("MagicNumber") // PreparedStatement のパラメータの位置

package io.eia.legacysim.app

import java.math.BigDecimal
import java.sql.Connection
import kotlin.random.Random

/**
 * レガシーのアプリの模擬: 受注表(t_juchu)を、レガシーのアプリと同じやり方で書き換える(ADR-0026)。
 *
 * - サーバは JST で動いている前提で、時刻はセッションのタイムゾーンを Asia/Tokyo にして `LOCALTIMESTAMP` で書く(タイムゾーンを持たない)。
 * - 登録では最終更新日時を書かない(既定の番兵値 9999-12-31 = 未設定)。更新では最終更新日時を書く。
 * - 受注番号は登録の後に変えない(業務の主キー)。
 *
 * [anomaly] は、ACL が変換できない値(DLQ に送る値)を持つ受注を、レガシーのバグや運用の誤りの模擬として登録する。
 * 各操作は、変えた受注の受注番号を返す。
 */
internal class LegacyJuchuApp(
    private val connection: Connection,
    private val random: Random = Random.Default,
) {
    /** 正常な受注を [count] 件登録する(状態は受付)。 */
    fun seed(count: Int): List<String> = List(count) { insert(Row.random(random)) }

    /** 受付・引当済の受注のうち、最大 [count] 件の状態を 1 段進める(受付 → 引当済 → 出荷済)。 */
    fun advance(count: Int): List<String> =
        transaction {
            val targets = pick("SELECT col_02 FROM t_juchu WHERE col_03 IN ('1', '2') ORDER BY random() LIMIT ?", count)
            targets.onEach { number ->
                update(
                    "UPDATE t_juchu SET col_03 = CASE col_03 WHEN '1' THEN '2' ELSE '3' END, col_08 = LOCALTIMESTAMP WHERE col_02 = ?",
                    number,
                )
            }
        }

    /** 出荷前の受注のうち、最大 [count] 件を取り消す(状態区分 9)。 */
    fun cancel(count: Int): List<String> =
        transaction {
            pick("SELECT col_02 FROM t_juchu WHERE col_03 IN ('1', '2') ORDER BY random() LIMIT ?", count).onEach { number ->
                update("UPDATE t_juchu SET col_03 = '9', col_08 = LOCALTIMESTAMP WHERE col_02 = ?", number)
            }
        }

    /** 最大 [count] 件の受注を物理削除する(レガシーは論理削除のフラグを持たない)。 */
    fun delete(count: Int): List<String> =
        transaction {
            pick("SELECT col_02 FROM t_juchu ORDER BY random() LIMIT ?", count).onEach { number ->
                update("DELETE FROM t_juchu WHERE col_02 = ?", number)
            }
        }

    /** ACL が変換できない値を 1 つ持つ受注を 1 件登録する。 */
    fun anomaly(kind: Anomaly): String =
        insert(
            Row.random(random).let { row ->
                when (kind) {
                    Anomaly.UNKNOWN_STATUS -> row.copy(status = UNKNOWN_STATUS_CODE)

                    Anomaly.AMOUNT_FRACTION -> row.copy(amount = FRACTION_AMOUNT)

                    Anomaly.AMOUNT_OUT_OF_RANGE -> row.copy(amount = OUT_OF_RANGE_AMOUNT)

                    // Shift_JIS の文字を UTF-8 として読み違え、置換文字(U+FFFD)になったまま保存された名前
                    Anomaly.GARBLED_NAME -> row.copy(customerName = GARBLED_CUSTOMER_NAME)
                }
            },
        )

    private fun insert(row: Row): String =
        transaction {
            val number =
                connection.prepareStatement("SELECT nextval('sq_juchu')").use { s ->
                    s.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val orderNumber = "J" + number.toString().padStart(ORDER_NUMBER_DIGITS, '0')
            connection.prepareStatement(INSERT).use { s ->
                s.setLong(1, number)
                s.setString(2, orderNumber)
                s.setString(3, row.status)
                s.setString(4, row.customerName)
                s.setString(5, row.customerCode)
                s.setBigDecimal(6, row.amount)
                s.executeUpdate()
            }
            orderNumber
        }

    private fun pick(
        sql: String,
        limit: Int,
    ): List<String> =
        connection.prepareStatement(sql).use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun update(
        sql: String,
        orderNumber: String,
    ) {
        connection.prepareStatement(sql).use { s ->
            s.setString(1, orderNumber)
            s.executeUpdate()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL TIME ZONE 'Asia/Tokyo'") }
            return block().also { connection.commit() }
        } catch (e: java.sql.SQLException) {
            connection.rollback()
            throw e
        }
    }

    private companion object {
        /** 受注番号(CHAR(10))の J に続く桁数。 */
        const val ORDER_NUMBER_DIGITS = 9
        const val INSERT =
            "INSERT INTO t_juchu (col_01, col_02, col_03, col_04, col_05, col_06, col_07) VALUES (?, ?, ?, ?, ?, ?, LOCALTIMESTAMP)"
        const val UNKNOWN_STATUS_CODE = "7"
        const val GARBLED_CUSTOMER_NAME = "ｶ)ﾃｽﾄ\uFFFD\uFFFD商事"
        val FRACTION_AMOUNT = BigDecimal("1234.50")

        /** 列(NUMERIC(13,2))には収まるが、契約の範囲(ADR-0026)を超える金額。 */
        val OUT_OF_RANGE_AMOUNT = BigDecimal("99999999999.00")
    }

    private data class Row(
        val status: String,
        val customerName: String,
        val customerCode: String,
        val amount: BigDecimal,
    ) {
        companion object {
            private val CUSTOMERS =
                listOf(
                    "C0000101" to "山田商事株式会社",
                    "C0000102" to "有限会社 佐藤製作所",
                    "C0000103" to "ｶ)ｽｽﾞｷ ｼｮｳｶｲ",
                    "C0000104" to "Tanaka Trading Co., Ltd.",
                )
            private const val MIN_YEN = 1_000
            private const val MAX_YEN = 500_000

            fun random(random: Random): Row {
                val (code, name) = CUSTOMERS[random.nextInt(CUSTOMERS.size)]
                // 金額の列は小数 2 桁。円なので小数部は常に .00
                return Row("1", name, code, BigDecimal(random.nextInt(MIN_YEN, MAX_YEN)).setScale(2))
            }
        }
    }
}

/** [LegacyJuchuApp.anomaly] で登録する、ACL が変換できない値の種類。 */
internal enum class Anomaly(
    val argument: String,
) {
    /** 対応表にない状態区分。 */
    UNKNOWN_STATUS("unknown-status"),

    /** 円の金額に小数部がある。 */
    AMOUNT_FRACTION("amount-fraction"),

    /** 契約の範囲を超える金額(列の桁には収まる)。 */
    AMOUNT_OUT_OF_RANGE("amount-out-of-range"),

    /** 文字化けした顧客名。 */
    GARBLED_NAME("garbled-name"),
    ;

    companion object {
        fun of(argument: String): Anomaly? = entries.firstOrNull { it.argument == argument }
    }
}
