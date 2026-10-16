package io.eia.batchetl.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * 日次の売上ファイルの 1 行(`contracts/files/sales_daily.v1.yaml`。INT-SALES-004。ADR-0031 §8)。
 * 個人情報は持たない: 顧客 ID は入れず、届け先は国と都道府県だけ。
 *
 * @property orderedAt 受け付けた時刻(UTC)
 * @property totalMinorUnits 合計金額(通貨の最小単位)
 * @property shipRegion 届け先の都道府県(ない注文は null)
 */
public data class SalesFileLine(
    val businessDate: LocalDate,
    val orderId: String,
    val orderedAt: Instant,
    val status: OrderStatus,
    val totalMinorUnits: Long,
    val currency: String,
    val shipCountry: String,
    val shipRegion: String?,
)

/**
 * 売上ファイルの版(名前)を決める規則(ADR-0031 §8)。
 *
 * ファイルの名前の時刻は、作った時刻ではなく **業務の日の締め(日本時間の翌日 00:00)** にし、連番は **実行の世代** にする。
 * - 同じ実行のやり直し(ステップのリトライ・落ちた後の再開)では同じ名前になり、上書きする(再実行安全)
 * - 作り直し(遅れて届いたデータでの RESTART)では世代が増えるので、新しい名前の別のファイルになる(受け手は新しい版として受け取る)
 */
public object SalesFileRevision {
    /** [day] の締めの時刻(UTC)。 */
    public fun cutoff(day: BusinessDay): Instant =
        day.date
            .plus(1, DateTimeUnit.DAY)
            .atTime(LocalTime(0, 0))
            .toInstant(BusinessDay.TIME_ZONE)
}
