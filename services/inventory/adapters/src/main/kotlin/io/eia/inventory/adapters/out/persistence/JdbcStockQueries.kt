package io.eia.inventory.adapters.out.persistence

import io.eia.inventory.application.port.outbound.StockQueries
import io.eia.inventory.domain.StockLevel
import io.eia.inventory.domain.StockSnapshot
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.OffsetDateTime
import kotlin.time.toKotlinInstant

/**
 * [StockQueries] の JDBC の実装(表 `stock`)。ロックを取らないので、引当の処理を待たせない(MVCC。読んだ時点の値で、引当を保証しない)。
 * 締め切りは [newTransaction] が `statement_timeout` で DB に伝える(ADR-0042 §6)。
 */
public class JdbcStockQueries(
    private val database: Database,
) : StockQueries {
    override suspend fun find(skus: Set<String>): Result<List<StockSnapshot>, DomainError> =
        database.newTransaction {
            database.inCurrentTransaction { connection ->
                connection.prepareStatement(FIND).use { statement ->
                    statement.setArray(1, connection.createArrayOf("varchar", skus.toTypedArray()))
                    statement.executeQuery().use { rows ->
                        ok(
                            buildList {
                                while (rows.next()) {
                                    val level = StockLevel(rows.getString("sku"), rows.getLong("on_hand"), rows.getLong("reserved"))
                                    val updatedAt = rows.getObject("updated_at", OffsetDateTime::class.java).toInstant().toKotlinInstant()
                                    add(StockSnapshot(level, updatedAt))
                                }
                            },
                        )
                    }
                }
            }
        }

    private companion object {
        const val FIND = "SELECT sku, on_hand, reserved, updated_at FROM stock WHERE sku = ANY(?)"
    }
}
