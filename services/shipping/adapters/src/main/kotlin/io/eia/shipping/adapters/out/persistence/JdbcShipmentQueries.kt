package io.eia.shipping.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.outbound.ShipmentQueries
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.RejectionReason
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentStatus
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.toKotlinInstant

/**
 * [ShipmentQueries] の JDBC の実装(表 `shipment`)。ロックを取らない。締め切りは [newTransaction] が `statement_timeout` で DB に伝える(ADR-0042 §6)。
 */
public class JdbcShipmentQueries(
    private val database: Database,
) : ShipmentQueries {
    override suspend fun find(orderIds: Set<String>): Result<List<Shipment>, DomainError> =
        database.newTransaction {
            database.inCurrentTransaction { connection ->
                connection.prepareStatement(FIND).use { statement ->
                    statement.setArray(1, connection.createArrayOf("varchar", orderIds.toTypedArray()))
                    statement.executeQuery().use { rows ->
                        ok(
                            buildList {
                                while (rows.next()) {
                                    add(
                                        Shipment(
                                            sagaId = rows.getString("saga_id"),
                                            orderId = rows.getString("order_id"),
                                            status = ShipmentStatus.valueOf(rows.getString("status")),
                                            shipmentId = rows.getString("shipment_id"),
                                            shippedAt = rows.getTimestamp("shipped_at")?.toInstant()?.toKotlinInstant(),
                                            rejection = rows.getString("rejection_reason")?.let(RejectionReason::valueOf),
                                            destination = rows.getString("destination_country")?.let(::Destination),
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

    private companion object {
        const val FIND =
            "SELECT saga_id, order_id, status, shipment_id, shipped_at, rejection_reason, destination_country " +
                "FROM shipment WHERE order_id = ANY(?)"
    }
}
