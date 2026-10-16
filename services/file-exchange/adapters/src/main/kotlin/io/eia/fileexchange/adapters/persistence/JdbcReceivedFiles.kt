package io.eia.fileexchange.adapters.persistence

import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * 受け取りの記録(`exchange.received_file`・`exchange.extra_arrival`。ADR-0032 §5)。
 * 同じ名前を 2 つ記録しないことは、主キーと部分一意索引(処理中は名前ごとに 1 つ)で DB が保証する。
 */
public class JdbcReceivedFiles(
    private val dataSource: DataSource,
) : ReceivedFiles {
    override suspend fun find(name: String): Result<ReceivedFile?, DomainError> = dataSource.inTransaction { select(it, name) }

    override suspend fun begin(file: ReceivedFile): Result<ReceivedFile, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                var index = 0
                statement.setString(++index, file.name)
                statement.setString(++index, file.sha256)
                statement.setString(++index, file.destination.name)
                statement.setString(++index, file.dataFile)
                statement.setString(++index, file.reason)
                statement.setString(++index, file.detail)
                statement.setString(++index, file.state.name)
                statement.executeUpdate()
            }
            checkNotNull(select(connection, file.name))
        }

    override suspend fun stored(name: String): Result<Unit, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(STORED).use { statement ->
                statement.setString(1, name)
                statement.executeUpdate()
            }
        }

    override suspend fun beginExtra(
        name: String,
        sha256: String?,
        reason: ExtraArrivalReason,
    ): Result<ExtraArrival, DomainError> =
        dataSource.inTransaction { connection ->
            val inserted =
                connection.prepareStatement(INSERT_EXTRA).use { statement ->
                    var index = 0
                    statement.setString(++index, name)
                    statement.setString(++index, sha256)
                    statement.setString(++index, reason.name)
                    statement.executeQuery().use { if (it.next()) extra(it) else null }
                }
            inserted ?: connection.prepareStatement(MOVING_EXTRA).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rs -> rs.next().let { extra(rs) } }
            }
        }

    override suspend fun movingExtras(): Result<List<ExtraArrival>, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(MOVING_EXTRAS).use { statement ->
                statement.executeQuery().use { rs -> buildList { while (rs.next()) add(extra(rs)) } }
            }
        }

    override suspend fun finishExtra(id: Long): Result<Unit, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(FINISH_EXTRA).use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate()
            }
        }

    private fun select(
        connection: Connection,
        name: String,
    ): ReceivedFile? =
        connection.prepareStatement(SELECT).use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    ReceivedFile(
                        name = rs.getString("file_name"),
                        sha256 = rs.getString("sha256"),
                        destination = ReceiptDestination.valueOf(rs.getString("destination")),
                        dataFile = rs.getString("data_file"),
                        reason = rs.getString("reason"),
                        detail = rs.getString("detail"),
                        state = ReceiptState.valueOf(rs.getString("state")),
                    )
                }
            }
        }

    private fun extra(rs: ResultSet): ExtraArrival =
        ExtraArrival(
            rs.getLong("id"),
            rs.getString("file_name"),
            rs.getString("sha256"),
            ExtraArrivalReason.valueOf(rs.getString("reason")),
        )

    private companion object {
        const val COLUMNS = "file_name, sha256, destination, data_file, reason, detail, state"
        const val SELECT = "SELECT $COLUMNS FROM exchange.received_file WHERE file_name = ?"
        const val INSERT = "INSERT INTO exchange.received_file ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (file_name) DO NOTHING"
        const val STORED =
            "UPDATE exchange.received_file SET state = 'STORED', stored_at = clock_timestamp() WHERE file_name = ? AND state = 'MOVING'"
        const val EXTRA_COLUMNS = "id, file_name, sha256, reason"
        const val INSERT_EXTRA =
            "INSERT INTO exchange.extra_arrival (file_name, sha256, reason, state) VALUES (?, ?, ?, 'MOVING') " +
                "ON CONFLICT (file_name) WHERE state = 'MOVING' DO NOTHING RETURNING $EXTRA_COLUMNS"
        const val MOVING_EXTRA = "SELECT $EXTRA_COLUMNS FROM exchange.extra_arrival WHERE file_name = ? AND state = 'MOVING'"
        const val MOVING_EXTRAS = "SELECT $EXTRA_COLUMNS FROM exchange.extra_arrival WHERE state = 'MOVING' ORDER BY id"
        const val FINISH_EXTRA =
            "UPDATE exchange.extra_arrival SET state = 'DONE', done_at = clock_timestamp() WHERE id = ? AND state = 'MOVING'"
    }
}
