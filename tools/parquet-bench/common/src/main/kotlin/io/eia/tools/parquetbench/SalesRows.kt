package io.eia.tools.parquetbench

import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat
import kotlin.random.Random

/** contracts/files/sales_daily.v1.yaml の 1 行。時刻は UTC のエポックからのマイクロ秒、日はエポックからの日数。 */
data class SalesRow(
    val businessDateEpochDay: Int,
    val orderId: String,
    val orderedAtMicros: Long,
    val status: String,
    val totalMinorUnits: Long,
    val currency: String,
    val shipCountry: String,
    val shipRegion: String?,
)

/**
 * 計測用の行を決まった順(order_id のバイト順)で作る。乱数の種を固定しているので、同じ件数なら毎回同じ行になる。
 * 実際の書き出し(④)と同じく、行をメモリに溜めずに 1 行ずつ渡す。
 */
object SalesRows {
    val BUSINESS_DATE: LocalDate = LocalDate.of(2026, 10, 14)
    private const val SEED = 20261014L
    private const val MICROS_PER_DAY = 86_400_000_000L
    private val STATUSES = listOf("PLACED", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED")
    private val OTHER_COUNTRIES = listOf("US", "KR", "TW", "CN")

    fun generate(rows: Int): Sequence<SalesRow> =
        sequence {
            val random = Random(SEED)
            val epochDay = BUSINESS_DATE.toEpochDay().toInt()
            val dayStartMicros = BUSINESS_DATE.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli() * 1_000
            // order_id の上位 64 ビットを単調に増やし、16 進の固定の桁数で書くので、文字列のバイト順と行の順が一致する
            val step = Long.MAX_VALUE / rows.coerceAtLeast(1)
            for (i in 0 until rows) {
                val high = i * step + random.nextLong(step)
                val low = random.nextLong()
                val japan = random.nextInt(100) < 97
                yield(
                    SalesRow(
                        businessDateEpochDay = epochDay,
                        orderId = uuid(high, low),
                        orderedAtMicros = dayStartMicros + random.nextLong(MICROS_PER_DAY),
                        status = STATUSES[weightedStatus(random)],
                        totalMinorUnits = 100L + random.nextLong(300_000L),
                        currency = "JPY",
                        shipCountry = if (japan) "JP" else OTHER_COUNTRIES[random.nextInt(OTHER_COUNTRIES.size)],
                        shipRegion = if (japan && random.nextInt(100) >= 2) "JP-%02d".format(1 + random.nextInt(47)) else null,
                    ),
                )
            }
        }

    /** 読み直した内容と比べるための要約。 */
    fun digest(rows: Sequence<SalesRow>): Digest {
        val md5 = MessageDigest.getInstance("MD5")
        var count = 0L
        var total = 0L
        var nullRegions = 0L
        var maxOrderedAt = Long.MIN_VALUE
        for (row in rows) {
            count++
            total += row.totalMinorUnits
            if (row.shipRegion == null) nullRegions++
            maxOrderedAt = maxOf(maxOrderedAt, row.orderedAtMicros)
            md5.update(row.orderId.toByteArray())
            md5.update('\n'.code.toByte())
        }
        return Digest(count, total, nullRegions, maxOrderedAt, HexFormat.of().formatHex(md5.digest()))
    }

    private fun weightedStatus(random: Random): Int =
        when (random.nextInt(100)) {
            in 0 until 10 -> 0
            in 10 until 25 -> 1
            in 25 until 45 -> 2
            in 45 until 95 -> 3
            else -> 4
        }

    private fun uuid(
        high: Long,
        low: Long,
    ): String {
        val hex = "%016x%016x".format(high, low)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/** 行の要約。orderIdsMd5 は order_id を行の順に改行でつないだものの MD5。 */
data class Digest(
    val count: Long,
    val totalMinorUnits: Long,
    val nullRegions: Long,
    val maxOrderedAtMicros: Long,
    val orderIdsMd5: String,
)
