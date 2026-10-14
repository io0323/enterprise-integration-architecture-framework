package io.eia.batchetl.adapters.persistence

import io.eia.batchetl.application.port.outbound.OrderMart
import io.eia.batchetl.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.sql.Date
import javax.sql.DataSource

/**
 * 日次のパーティション(`mart.order_daily`)を、取り込んだ行(`raw`)から SQL で作る(ELT の変換。ADR-0031 §4)。
 *
 * [rebuild] は 1 つのトランザクションで、その日の行を消して入れ直し(パーティションの置換)、作り直しの印を消す。
 * 何度実行しても結果は同じ(再実行安全。Framework 7.4)。途中で落ちれば何も変わらない。
 *
 * 作り直しの間は、印の表(`etl.dirty_day`)を `SHARE ROW EXCLUSIVE` でロックする。取り込みの印の追加はロックが外れるまで待つので、
 * 作り直しが見た後に届いた行の印は、印を消した後に付き、次の実行で作り直される(取りこぼさない)。
 */
public class JdbcOrderMart(
    private val dataSource: DataSource,
) : OrderMart {
    override suspend fun dirtyDays(): Result<List<BusinessDay>, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement("SELECT business_date FROM etl.dirty_day ORDER BY business_date").use { statement ->
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(BusinessDay.of(rs.getDate(1).toLocalDate().toKotlinLocalDate())) }
                }
            }
        }

    override suspend fun rebuild(day: BusinessDay): Result<Int, DomainError> =
        dataSource.inTransaction { connection ->
            val date = Date.valueOf(day.date.toJavaLocalDate())
            connection.createStatement().use { it.execute("LOCK TABLE etl.dirty_day IN SHARE ROW EXCLUSIVE MODE") }
            connection.prepareStatement("DELETE FROM mart.order_daily WHERE business_date = ?").use {
                it.setDate(1, date)
                it.executeUpdate()
            }
            val count =
                connection.prepareStatement(INSERT_DAY).use {
                    it.setDate(1, date)
                    it.executeUpdate()
                }
            connection.prepareStatement("DELETE FROM etl.dirty_day WHERE business_date = ?").use {
                it.setDate(1, date)
                it.executeUpdate()
            }
            connection.prepareStatement(RECORD_BUILD).use {
                it.setDate(1, date)
                it.setInt(2, count)
                it.executeUpdate()
            }
            count
        }

    private companion object {
        // 今の状態は版の最も大きい遷移(なければ PLACED)。取消の理由は取消のイベントから
        val INSERT_DAY =
            """
            INSERT INTO mart.order_daily (business_date, order_id, customer_id, ordered_at, status, status_changed_at, cancel_reason,
                                          total_minor_units, currency, line_count, ship_country, ship_region)
            SELECT p.business_date, p.order_id, p.customer_id, p.ordered_at, coalesce(s.status, 'PLACED'), s.changed_at, c.reason,
                   p.total_minor_units, p.currency, p.line_count, p.ship_country, p.ship_region
            FROM raw.order_placed p
            LEFT JOIN LATERAL (
                SELECT sc.status, sc.changed_at FROM raw.order_status_change sc
                WHERE sc.order_id = p.order_id ORDER BY sc.version DESC LIMIT 1
            ) s ON true
            LEFT JOIN raw.order_cancelled c ON c.order_id = p.order_id
            WHERE p.business_date = ?
            """.trimIndent()
        val RECORD_BUILD =
            """
            INSERT INTO mart.partition_build (business_date, order_count, built_at) VALUES (?, ?, clock_timestamp())
            ON CONFLICT (business_date) DO UPDATE SET order_count = EXCLUDED.order_count, built_at = EXCLUDED.built_at
            """.trimIndent()
    }
}
