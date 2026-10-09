package io.eia.inventory.adapters.out.persistence

import io.eia.inventory.application.port.outbound.ReservationStore
import io.eia.inventory.domain.RejectionReason
import io.eia.inventory.domain.Reservation
import io.eia.inventory.domain.ReservationStatus
import io.eia.inventory.domain.StockLine
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.SQLException
import kotlin.time.Duration

/**
 * [ReservationStore] の JDBC の実装(表 `reservation` / `reservation_line`。db/inventory/V1)。
 * 終わった時刻(`settled_at`)は DB の時計(`clock_timestamp()`)で書き、保持期間の判定も DB の時計で行う(ADR-0029 §5)。
 */
public class JdbcReservationStore(
    private val database: Database,
) : ReservationStore {
    override suspend fun findForUpdate(sagaId: String): Result<Reservation?, DomainError> =
        database.inCurrentTransaction { connection ->
            val header =
                connection.prepareStatement(SELECT_FOR_UPDATE).use { statement ->
                    statement.setString(1, sagaId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            Triple(
                                rows.getString("order_id"),
                                ReservationStatus.valueOf(rows.getString("status")),
                                rows.getString("rejection_reason")?.let(RejectionReason::valueOf),
                            )
                        } else {
                            null
                        }
                    }
                }
            ok(header?.let { (orderId, status, rejection) -> Reservation(sagaId, orderId, status, rejection, lines(connection, sagaId)) })
        }

    override suspend fun insert(reservation: Reservation): Result<Unit, DomainError> =
        database.inCurrentTransaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                var index = 0
                statement.setString(++index, reservation.sagaId)
                statement.setString(++index, reservation.orderId)
                statement.setString(++index, reservation.status.name)
                statement.setString(++index, reservation.rejection?.name)
                statement.setBoolean(++index, reservation.status.isSettled)
                statement.executeUpdate()
            }
            if (reservation.lines.isNotEmpty()) {
                connection.prepareStatement(INSERT_LINE).use { statement ->
                    reservation.lines.forEach { line ->
                        var column = 0
                        statement.setString(++column, reservation.sagaId)
                        statement.setInt(++column, line.lineNumber)
                        statement.setString(++column, line.sku)
                        statement.setLong(++column, line.quantity)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            ok(Unit)
        }

    override suspend fun markReleased(sagaId: String): Result<Unit, DomainError> =
        database.inCurrentTransaction { connection ->
            val updated =
                connection.prepareStatement(MARK_RELEASED).use { statement ->
                    statement.setString(1, sagaId)
                    statement.executeUpdate()
                }
            if (updated == 1) ok(Unit) else err(UnexpectedError("引き当てている記録がありません(状態が変わった)"))
        }

    override suspend fun purgeSettled(
        retention: Duration,
        batchSize: Int,
    ): Result<Int, DomainError> =
        try {
            // 1 回ずつ短いトランザクションで消す(長いロックを避ける)。明細は外部キーの ON DELETE CASCADE で消える
            ok(
                database.newTransaction {
                    jdbcConnection().prepareStatement(PURGE).use { statement ->
                        statement.setLong(1, retention.inWholeSeconds)
                        statement.setInt(2, batchSize)
                        statement.executeUpdate()
                    }
                },
            )
        } catch (e: SQLException) {
            err(SqlErrors.classify(e))
        }

    private fun lines(
        connection: Connection,
        sagaId: String,
    ): List<StockLine> =
        connection.prepareStatement(SELECT_LINES).use { statement ->
            statement.setString(1, sagaId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(StockLine(rows.getInt("line_number"), rows.getString("sku"), rows.getLong("quantity")))
                }
            }
        }

    private companion object {
        const val SELECT_FOR_UPDATE = "SELECT order_id, status, rejection_reason FROM reservation WHERE saga_id = ? FOR UPDATE"
        const val SELECT_LINES = "SELECT line_number, sku, quantity FROM reservation_line WHERE saga_id = ? ORDER BY line_number"
        val INSERT =
            """
            INSERT INTO reservation (saga_id, order_id, status, rejection_reason, settled_at)
            VALUES (?, ?, ?, ?, CASE WHEN ? THEN clock_timestamp() END)
            """.trimIndent()
        const val INSERT_LINE = "INSERT INTO reservation_line (saga_id, line_number, sku, quantity) VALUES (?, ?, ?, ?)"
        val MARK_RELEASED =
            """
            UPDATE reservation SET status = 'RELEASED', settled_at = clock_timestamp()
            WHERE saga_id = ? AND status = 'RESERVED'
            """.trimIndent()
        val PURGE =
            """
            DELETE FROM reservation WHERE saga_id IN (
                SELECT saga_id FROM reservation
                WHERE settled_at < clock_timestamp() - make_interval(secs => ?)
                ORDER BY settled_at
                LIMIT ?
            )
            """.trimIndent()
    }
}
