package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderCount
import io.eia.order.application.port.outbound.OrderSummaries
import io.eia.order.domain.DailyOrderDigest
import io.eia.order.domain.OrderStatus
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.HexFormat
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * [OrderSummaries] の PostgreSQL の実装(日次の集計。ADR-0031 §3)。
 *
 * 注文 ID の **バイト順** に並べる(`COLLATE "C"`)。DB の既定の照合順序は記号を飛ばして比べることがあり、分析の側と並びが変わるため。
 * 行は 1 件ずつハッシュに足し、全件をメモリに持たない。
 */
public class ExposedOrderSummaries internal constructor(
    private val session: JdbcSession,
) : OrderSummaries {
    public constructor(database: Database) : this(ExposedJdbcSession(database))

    override suspend fun count(
        from: Instant,
        until: Instant,
    ): Result<OrderCount, DomainError> =
        session.run { connection ->
            connection.prepareStatement(SELECT).use { statement ->
                statement.setTimestamp(1, Timestamp.from(from.toJavaInstant()))
                statement.setTimestamp(2, Timestamp.from(until.toJavaInstant()))
                statement.executeQuery().use { rows -> ok(digestOf(rows)) }
            }
        }

    /** 行を 1 件ずつハッシュに足す(行の間に区切りを入れ、最後の行の後には入れない)。 */
    private fun digestOf(rows: ResultSet): OrderCount {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        while (rows.next()) {
            if (count > 0) digest.update(DailyOrderDigest.LINE_SEPARATOR.toByteArray(Charsets.UTF_8))
            val line =
                DailyOrderDigest.line(
                    rows.getString("id"),
                    OrderStatus.valueOf(rows.getString("status")),
                    rows.getLong("total_amount_minor"),
                    rows.getString("currency"),
                )
            digest.update(line.toByteArray(Charsets.UTF_8))
            count++
        }
        return OrderCount(count, HexFormat.of().formatHex(digest.digest()))
    }

    private companion object {
        const val SELECT =
            "SELECT id, status, total_amount_minor, currency FROM orders " +
                "WHERE ordered_at >= ? AND ordered_at < ? ORDER BY id COLLATE \"C\""
    }
}
