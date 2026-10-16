package io.eia.fileexchange.adapters.persistence

import io.eia.fileexchange.application.port.outbound.Deliveries
import io.eia.fileexchange.domain.Delivery
import io.eia.fileexchange.domain.DeliveryState
import io.eia.fileexchange.domain.PartnerId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import java.sql.Connection
import javax.sql.DataSource

/** 取引先への配送の記録(`exchange.delivery`。ADR-0032 §8)。組ごとに 1 行であることは主キーで DB が保証する。 */
public class JdbcDeliveries(
    private val dataSource: DataSource,
) : Deliveries {
    override suspend fun find(
        name: String,
        partner: PartnerId,
    ): Result<Delivery?, DomainError> = dataSource.inTransaction { select(it, name, partner) }

    override suspend fun begin(
        name: String,
        partner: PartnerId,
        remoteName: String,
    ): Result<Delivery, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                var index = 0
                statement.setString(++index, name)
                statement.setString(++index, partner.value)
                statement.setString(++index, remoteName)
                statement.executeUpdate()
            }
            checkNotNull(select(connection, name, partner))
        }

    override suspend fun sent(
        name: String,
        partner: PartnerId,
        alreadyPresent: Boolean,
    ): Result<Unit, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(SENT).use { statement ->
                var index = 0
                statement.setBoolean(++index, alreadyPresent)
                statement.setString(++index, name)
                statement.setString(++index, partner.value)
                statement.executeUpdate()
            }
        }

    private fun select(
        connection: Connection,
        name: String,
        partner: PartnerId,
    ): Delivery? =
        connection.prepareStatement(SELECT).use { statement ->
            statement.setString(1, name)
            statement.setString(2, partner.value)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    Delivery(
                        name = rs.getString("file_name"),
                        partner = PartnerId(rs.getString("partner_id")),
                        remoteName = rs.getString("remote_name"),
                        state = DeliveryState.valueOf(rs.getString("state")),
                        alreadyPresent = rs.getBoolean("already_present"),
                    )
                }
            }
        }

    private companion object {
        const val SELECT =
            "SELECT file_name, partner_id, remote_name, state, already_present FROM exchange.delivery " +
                "WHERE file_name = ? AND partner_id = ?"
        const val INSERT =
            "INSERT INTO exchange.delivery (file_name, partner_id, remote_name, state) VALUES (?, ?, ?, 'SENDING') " +
                "ON CONFLICT (file_name, partner_id) DO NOTHING"
        const val SENT =
            "UPDATE exchange.delivery SET state = 'SENT', already_present = ?, sent_at = clock_timestamp() " +
                "WHERE file_name = ? AND partner_id = ? AND state = 'SENDING'"
    }
}
