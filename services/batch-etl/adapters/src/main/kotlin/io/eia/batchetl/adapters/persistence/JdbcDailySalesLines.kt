package io.eia.batchetl.adapters.persistence

import io.eia.batchetl.application.port.outbound.DailySalesLines
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.OrderStatus
import io.eia.batchetl.domain.SalesFileLine
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.toKotlinInstant

/**
 * 日次のパーティション(`mart.order_daily`)から、売上ファイルの行を読む(ADR-0031 §8)。
 *
 * - 行は注文 ID の **バイト順(UTF-8)**(`ORDER BY order_id COLLATE "C"`。契約の orderBy。照合の [JdbcMartSummaries] と同じ)。
 * - 読み取りは 1 つの読み取り専用のトランザクションで、カーソル([FETCH_SIZE] 行ずつ)で読む。その日の行を全部メモリに載せない。
 * - パーティションがまだできていない日(`mart.partition_build` にない)は読まない([ConflictError])。0 件のファイルと区別するため。
 */
public class JdbcDailySalesLines(
    private val dataSource: DataSource,
) : DailySalesLines {
    override suspend fun <T> read(
        day: BusinessDay,
        consume: suspend (Sequence<SalesFileLine>) -> Result<T, DomainError>,
    ): Result<T, DomainError> =
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    connection.isReadOnly = true
                    try {
                        readIn(connection, day, consume)
                    } finally {
                        connection.rollback()
                    }
                }
            } catch (e: SQLException) {
                err(classify(e))
            }
        }

    private suspend fun <T> readIn(
        connection: Connection,
        day: BusinessDay,
        consume: suspend (Sequence<SalesFileLine>) -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        val date = Date.valueOf(day.date.toJavaLocalDate())
        val built =
            connection.prepareStatement(BUILT).use { statement ->
                statement.setDate(1, date)
                statement.executeQuery().use { it.next() }
            }
        if (!built) return err(ConflictError("$day の日次のパーティションがまだできていません"))
        return connection.prepareStatement(ROWS).use { statement ->
            statement.setDate(1, date)
            statement.fetchSize = FETCH_SIZE
            statement.executeQuery().use { rs -> consume(generateSequence { if (rs.next()) line(rs) else null }) }
        }
    }

    private fun line(rs: ResultSet): SalesFileLine =
        SalesFileLine(
            businessDate = rs.getDate("business_date").toLocalDate().toKotlinLocalDate(),
            orderId = rs.getString("order_id"),
            orderedAt = rs.getTimestamp("ordered_at").toInstant().toKotlinInstant(),
            status = OrderStatus.valueOf(rs.getString("status")),
            totalMinorUnits = rs.getLong("total_minor_units"),
            currency = rs.getString("currency"),
            shipCountry = rs.getString("ship_country"),
            shipRegion = rs.getString("ship_region"),
        )

    private companion object {
        const val FETCH_SIZE = 1_000
        const val BUILT = "SELECT 1 FROM mart.partition_build WHERE business_date = ?"
        const val ROWS =
            "SELECT business_date, order_id, ordered_at, status, total_minor_units, currency, ship_country, ship_region " +
                "FROM mart.order_daily WHERE business_date = ? ORDER BY order_id COLLATE \"C\""
    }
}
