package io.eia.shipping.adapters.out.persistence

import io.eia.platform.messagingkafka.EventIds
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.outbound.ShipmentStamps
import io.eia.shipping.application.port.outbound.ShipmentStore
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.RejectionReason
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentStamp
import io.eia.shipping.domain.ShipmentStatus
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.SQLException
import java.sql.Timestamp
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * [ShipmentStore] の JDBC の実装(表 `shipment`。db/shipping/V1)。
 * 終わった時刻(`settled_at`)は DB の時計(`clock_timestamp()`)で書き、保持期間の判定も DB の時計で行う(ADR-0029 §5)。
 *
 * 出荷の記録は書き換えない(アプリのロールに UPDATE を付けない)ので、[findForUpdate] は行をロックしない(`FOR UPDATE` には UPDATE の権限が要る)。
 * 行があれば変わらず、なければ同時の挿入を主キーが 1 つに絞る(後の側は 23505 → Transient → やり直しで返し直しになる)。
 */
public class JdbcShipmentStore(
    private val database: Database,
) : ShipmentStore {
    override suspend fun findForUpdate(sagaId: String): Result<Shipment?, DomainError> =
        database.inCurrentTransaction { connection ->
            connection.prepareStatement(SELECT_FOR_UPDATE).use { statement ->
                statement.setString(1, sagaId)
                statement.executeQuery().use { rows ->
                    ok(
                        if (rows.next()) {
                            Shipment(
                                sagaId = sagaId,
                                orderId = rows.getString("order_id"),
                                status = ShipmentStatus.valueOf(rows.getString("status")),
                                shipmentId = rows.getString("shipment_id"),
                                shippedAt = rows.getTimestamp("shipped_at")?.toInstant()?.toKotlinInstant(),
                                rejection = rows.getString("rejection_reason")?.let(RejectionReason::valueOf),
                                destination = rows.getString("destination_country")?.let(::Destination),
                            )
                        } else {
                            null
                        },
                    )
                }
            }
        }

    override suspend fun insert(shipment: Shipment): Result<Unit, DomainError> =
        database.inCurrentTransaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                var index = 0
                statement.setString(++index, shipment.sagaId)
                statement.setString(++index, shipment.orderId)
                statement.setString(++index, shipment.status.name)
                statement.setString(++index, shipment.shipmentId)
                statement.setTimestamp(++index, shipment.shippedAt?.let { Timestamp.from(it.toJavaInstant()) })
                statement.setString(++index, shipment.rejection?.name)
                statement.setString(++index, shipment.destination?.countryCode)
                statement.setBoolean(++index, shipment.status.isSettled)
                statement.executeUpdate()
            }
            ok(Unit)
        }

    override suspend fun purgeSettled(
        retention: Duration,
        batchSize: Int,
    ): Result<Int, DomainError> =
        try {
            // 1 回ずつ短いトランザクションで消す(長いロックを避ける)
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

    private companion object {
        val SELECT_FOR_UPDATE =
            """
            SELECT order_id, status, shipment_id, shipped_at, rejection_reason, destination_country
            FROM shipment WHERE saga_id = ?
            """.trimIndent()
        val INSERT =
            """
            INSERT INTO shipment (saga_id, order_id, status, shipment_id, shipped_at, rejection_reason, destination_country, settled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN clock_timestamp() END)
            """.trimIndent()
        val PURGE =
            """
            DELETE FROM shipment WHERE saga_id IN (
                SELECT saga_id FROM shipment
                WHERE settled_at < clock_timestamp() - make_interval(secs => ?)
                ORDER BY settled_at
                LIMIT ?
            )
            """.trimIndent()
    }
}

/**
 * 出荷 ID(UUIDv7)と出荷の時刻。時刻はマイクロ秒に切り詰める(契約の `timestamp-micros` と DB の精度に合わせ、
 * 記録から返し直した返事と最初の返事を同じにする)。
 */
public class UuidV7ShipmentStamps(
    private val clock: Clock = Clock.System,
    private val ids: EventIds = EventIds(clock),
) : ShipmentStamps {
    override fun next(): ShipmentStamp {
        val now = clock.now()
        val micros = kotlin.time.Instant.fromEpochSeconds(now.epochSeconds, now.nanosecondsOfSecond / NANOS_PER_MICRO * NANOS_PER_MICRO)
        return ShipmentStamp(ids.next().toString(), micros)
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000
    }
}
