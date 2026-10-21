package io.eia.integrationflow.adapters.out.persistence

import io.eia.integrationflow.application.port.outbound.SyncWatermarks
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Timestamp
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** [SyncWatermarks] の JDBC の実装(表 `sync_watermark`。主キーは Flow と Connector)。それぞれ 1 つのトランザクションで読み書きする。 */
public class JdbcSyncWatermarks(
    private val database: Database,
) : SyncWatermarks {
    override suspend fun get(
        flowId: String,
        connector: String,
    ): Result<Instant?, DomainError> =
        database.newTransaction {
            database.inCurrentTransaction { connection ->
                connection.prepareStatement(SELECT).use { statement ->
                    statement.setString(1, flowId)
                    statement.setString(2, connector)
                    statement.executeQuery().use { rs -> ok(if (rs.next()) rs.getTimestamp(1).toInstant().toKotlinInstant() else null) }
                }
            }
        }

    override suspend fun set(
        flowId: String,
        connector: String,
        watermark: Instant,
    ): Result<Unit, DomainError> =
        database.newTransaction {
            database.inCurrentTransaction { connection ->
                connection.prepareStatement(UPSERT).use { statement ->
                    var index = 0
                    statement.setString(++index, flowId)
                    statement.setString(++index, connector)
                    statement.setTimestamp(++index, Timestamp.from(watermark.toJavaInstant()))
                    statement.executeUpdate()
                    ok(Unit)
                }
            }
        }

    private companion object {
        const val SELECT = "SELECT watermark FROM sync_watermark WHERE flow_id = ? AND connector = ?"
        const val UPSERT =
            "INSERT INTO sync_watermark (flow_id, connector, watermark, updated_at) VALUES (?, ?, ?, clock_timestamp()) " +
                "ON CONFLICT (flow_id, connector) DO UPDATE SET watermark = EXCLUDED.watermark, updated_at = EXCLUDED.updated_at"
    }
}
