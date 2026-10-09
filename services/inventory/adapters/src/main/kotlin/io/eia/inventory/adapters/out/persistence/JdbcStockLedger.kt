package io.eia.inventory.adapters.out.persistence

import io.eia.inventory.application.port.outbound.StockLedger
import io.eia.inventory.domain.StockLevel
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [StockLedger] の JDBC の実装(表 `stock`)。
 *
 * - ロックは SKU の昇順(`ORDER BY sku FOR UPDATE`)。同時の引当・解放のトランザクションが同じ順にロックするので、デッドロックしない。
 * - 引当の数の更新は、ロックの後に読んだ値で判定した結果を書く。DB の制約(`0 <= reserved <= on_hand`)でも、在庫が負にならないことを守る。
 */
public class JdbcStockLedger(
    private val database: Database,
) : StockLedger {
    override suspend fun lock(skus: Set<String>): Result<Map<String, StockLevel>, DomainError> {
        if (skus.isEmpty()) return ok(emptyMap())
        return database.inCurrentTransaction { connection ->
            connection.prepareStatement(LOCK).use { statement ->
                statement.setArray(1, connection.createArrayOf("varchar", skus.sorted().toTypedArray()))
                statement.executeQuery().use { rows ->
                    ok(
                        buildMap {
                            while (rows.next()) {
                                val sku = rows.getString("sku")
                                put(sku, StockLevel(sku, rows.getLong("on_hand"), rows.getLong("reserved")))
                            }
                        },
                    )
                }
            }
        }
    }

    override suspend fun adjustReserved(deltas: Map<String, Long>): Result<Unit, DomainError> {
        if (deltas.isEmpty()) return ok(Unit)
        return database.inCurrentTransaction { connection ->
            val updated =
                connection.prepareStatement(ADJUST).use { statement ->
                    deltas.toSortedMap().forEach { (sku, delta) ->
                        statement.setLong(1, delta)
                        statement.setString(2, sku)
                        statement.addBatch()
                    }
                    statement.executeBatch().sum()
                }
            if (updated == deltas.size) ok(Unit) else err(UnexpectedError("在庫の行がありません"))
        }
    }

    private companion object {
        const val LOCK = "SELECT sku, on_hand, reserved FROM stock WHERE sku = ANY(?) ORDER BY sku FOR UPDATE"
        const val ADJUST = "UPDATE stock SET reserved = reserved + ?, updated_at = clock_timestamp() WHERE sku = ?"
    }
}
