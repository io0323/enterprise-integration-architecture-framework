package io.eia.b2bgateway.adapters.persistence

import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import javax.sql.DataSource

/**
 * 応答の交換の管理番号(UNB / UNZ の 0020。an..14。ADR-0041 §5)。宛先の取引先の識別子ごとに、1 から 1 ずつ増やす(行のロックで直列)。
 * 番号を振った後に記録が失敗すると欠番になるが、取引先にとって管理番号は一意であればよい。
 */
public class JdbcControlReferences(
    private val dataSource: DataSource,
) : ControlReferences {
    override suspend fun next(recipient: PartyIdentification): Result<String, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(NEXT).use { statement ->
                statement.setString(1, recipient.id)
                statement.setString(2, recipient.qualifier)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1).toString()
                }
            }
        }

    private companion object {
        const val NEXT = """
            INSERT INTO b2b.control_reference (party_id, party_qualifier, last_value) VALUES (?, ?, 1)
            ON CONFLICT (party_id, party_qualifier) DO UPDATE SET last_value = b2b.control_reference.last_value + 1
            RETURNING last_value
        """
    }
}
