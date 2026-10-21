package io.eia.integrationflow.adapters.out.persistence

import io.eia.integrationflow.application.port.outbound.FlowVersions
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection

/**
 * [FlowVersions] の JDBC の実装(表 `flow_version`。主キーは Flow とキー)。Webhook の経路と定期の同期の経路が同じ表を使う(ADR-0035 §4)。
 *
 * 記録より新しい版のときだけ `INSERT ... ON CONFLICT DO UPDATE ... WHERE version < EXCLUDED.version` で進める(1 文で判定と更新をする)。
 * 同じキーの同時の更新では、後の方は行のロックで先の方の確定を待ち、先の方が進めた版と比べる(古い版で上書きしない)。
 */
public class JdbcFlowVersions(
    private val database: Database,
) : FlowVersions {
    override suspend fun advance(
        flowId: String,
        key: String,
        version: Long,
    ): Result<VersionDecision, DomainError> =
        database.inCurrentTransaction { connection ->
            if (advanced(connection, flowId, key, version)) {
                ok(VersionDecision.NEWER)
            } else {
                ok(VersionDecision.of(recorded(connection, flowId, key), version))
            }
        }

    private fun advanced(
        connection: Connection,
        flowId: String,
        key: String,
        version: Long,
    ): Boolean =
        connection.prepareStatement(ADVANCE).use { statement ->
            var index = 0
            statement.setString(++index, flowId)
            statement.setString(++index, key)
            statement.setLong(++index, version)
            statement.executeUpdate() == 1
        }

    private fun recorded(
        connection: Connection,
        flowId: String,
        key: String,
    ): Long? =
        connection.prepareStatement(SELECT).use { statement ->
            statement.setString(1, flowId)
            statement.setString(2, key)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private companion object {
        const val ADVANCE =
            "INSERT INTO flow_version (flow_id, record_key, version, updated_at) VALUES (?, ?, ?, clock_timestamp()) " +
                "ON CONFLICT (flow_id, record_key) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at " +
                "WHERE flow_version.version < EXCLUDED.version"
        const val SELECT = "SELECT version FROM flow_version WHERE flow_id = ? AND record_key = ?"
    }
}
