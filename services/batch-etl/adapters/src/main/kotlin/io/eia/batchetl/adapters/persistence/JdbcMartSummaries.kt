package io.eia.batchetl.adapters.persistence

import io.eia.batchetl.application.port.outbound.MartSummaries
import io.eia.batchetl.application.port.outbound.ReconciliationLog
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DailyOrderDigest
import io.eia.batchetl.domain.DailySummary
import io.eia.batchetl.domain.DayReconciliation
import io.eia.batchetl.domain.OrderStatus
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlinx.datetime.toJavaLocalDate
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.sql.Types
import java.util.HexFormat
import javax.sql.DataSource

/**
 * 分析の日次のパーティションの集計(件数とハッシュ。ADR-0031 §3・§6)。order-service と同じ作り方にする。
 *
 * - 行は注文 ID の **バイト順(UTF-8)** に並べる(`ORDER BY order_id COLLATE "C"`)。DB の既定の照合順序が言語的な並びでも、
 *   order-service と並びが変わらないように明示する(統合テストは ICU の言語的な並びの DB で確かめる)。
 * - 行はストリームでハッシュに入れ、その日の注文を全部メモリに載せない。
 */
public class JdbcMartSummaries(
    private val dataSource: DataSource,
) : MartSummaries,
    ReconciliationLog {
    override suspend fun summary(day: BusinessDay): Result<DailySummary?, DomainError> =
        dataSource.inTransaction { connection ->
            val date = Date.valueOf(day.date.toJavaLocalDate())
            if (!built(connection, date)) {
                null
            } else {
                connection.prepareStatement(ROWS).use { statement ->
                    statement.setDate(1, date)
                    statement.fetchSize = FETCH_SIZE
                    statement.executeQuery().use { rs -> digestOf(day, rs) }
                }
            }
        }

    override suspend fun record(
        runDay: BusinessDay,
        results: List<DayReconciliation>,
    ): Result<Unit, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(RECORD).use { statement ->
                results.forEach { result ->
                    var index = 0
                    statement.setDate(++index, Date.valueOf(runDay.date.toJavaLocalDate()))
                    statement.setDate(++index, Date.valueOf(result.day.date.toJavaLocalDate()))
                    statement.setString(++index, result.outcome.name)
                    setNullableLong(statement, ++index, result.source?.orderCount)
                    statement.setString(++index, result.source?.digest)
                    setNullableLong(statement, ++index, result.mart?.orderCount)
                    statement.setString(++index, result.mart?.digest)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            Unit
        }

    private fun built(
        connection: Connection,
        date: Date,
    ): Boolean =
        connection.prepareStatement("SELECT 1 FROM mart.partition_build WHERE business_date = ?").use { statement ->
            statement.setDate(1, date)
            statement.executeQuery().use { it.next() }
        }

    private fun digestOf(
        day: BusinessDay,
        rs: ResultSet,
    ): DailySummary {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        while (rs.next()) {
            if (count > 0) digest.update(DailyOrderDigest.LINE_SEPARATOR.toByteArray(Charsets.UTF_8))
            val line =
                DailyOrderDigest.line(
                    rs.getString("order_id"),
                    OrderStatus.valueOf(rs.getString("status")),
                    rs.getLong("total_minor_units"),
                    rs.getString("currency"),
                )
            digest.update(line.toByteArray(Charsets.UTF_8))
            count++
        }
        return DailySummary(day, count, HexFormat.of().formatHex(digest.digest()))
    }

    private fun setNullableLong(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
    }

    private companion object {
        const val FETCH_SIZE = 1_000
        const val ROWS =
            "SELECT order_id, status, total_minor_units, currency FROM mart.order_daily " +
                "WHERE business_date = ? ORDER BY order_id COLLATE \"C\""
        val RECORD =
            """
            INSERT INTO mart.reconciliation (run_date, business_date, outcome, source_count, source_digest, mart_count, mart_digest, checked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp())
            ON CONFLICT (run_date, business_date) DO UPDATE
                SET outcome = EXCLUDED.outcome, source_count = EXCLUDED.source_count, source_digest = EXCLUDED.source_digest,
                    mart_count = EXCLUDED.mart_count, mart_digest = EXCLUDED.mart_digest, checked_at = EXCLUDED.checked_at
            """.trimIndent()
    }
}
