package io.eia.batchetl.adapters.persistence

import io.eia.batchetl.application.port.outbound.RawOrderStore
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.OrderRecord
import io.eia.platform.batch.CheckpointKey
import io.eia.platform.batch.DagId
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * 取り込んだ行(スキーマ `raw`)と位置(バッチの Watermark。ADR-0030 §3)を、1 つのトランザクションで確定する(ADR-0031 §4)。
 *
 * - 行は自然なキー(注文 ID・注文 ID と版)で冪等に入れる(`ON CONFLICT DO NOTHING`)。kill の後に同じイベントを読み直しても重複しない。
 * - 新しく入った行の業務の日に、作り直しの印(`etl.dirty_day`)を付ける。状態の遷移・取消は、受け付けの行がある注文の日に付ける
 *   (受け付けがまだ届いていなければ、受け付けが届いたときに付く)。
 * - 印は [JdbcOrderMart.rebuild] の表のロックと競合する(作り直しの間に届いた分の印は、作り直しの後に残る)。
 */
public class JdbcRawOrderStore(
    private val dataSource: DataSource,
    private val batchStore: JdbcBatchStore,
    private val watermark: CheckpointKey = WATERMARK,
    private val clock: Clock = Clock.System,
) : RawOrderStore {
    override suspend fun position(): Result<SourcePosition?, DomainError> =
        withContext(Dispatchers.IO) { batchStore.readCheckpoint(watermark) }
            .mapError { it.asDomainError() }
            .map { value -> value?.let(::SourcePosition) }

    override suspend fun append(
        records: List<OrderRecord>,
        position: SourcePosition,
    ): Result<Unit, DomainError> =
        dataSource.transaction { connection ->
            val days = mutableSetOf<BusinessDay>()
            val touched = mutableSetOf<String>()
            records.forEach { record ->
                when (record) {
                    is OrderRecord.Placed -> if (insertPlaced(connection, record)) days += record.day
                    is OrderRecord.StatusChanged -> if (insertStatusChange(connection, record)) touched += record.orderId
                    is OrderRecord.Cancelled -> if (insertCancelled(connection, record)) touched += record.orderId
                }
            }
            markDirty(connection, days, touched)
            // 位置を書けなければ、行も取り消す(Err で取り消す)
            batchStore.writeCheckpoint(connection, watermark, position.value, clock.now()).mapError { it.asDomainError() }
        }

    private fun insertPlaced(
        connection: Connection,
        record: OrderRecord.Placed,
    ): Boolean =
        connection.prepareStatement(INSERT_PLACED).use { statement ->
            var index = 0
            statement.setString(++index, record.orderId)
            statement.setString(++index, record.customerId)
            statement.setTimestamp(++index, timestamp(record.orderedAt))
            statement.setDate(++index, Date.valueOf(record.day.date.toJavaLocalDate()))
            statement.setLong(++index, record.totalMinorUnits)
            statement.setString(++index, record.currency)
            statement.setInt(++index, record.lineCount)
            statement.setString(++index, record.area.countryCode)
            statement.setString(++index, record.area.region)
            statement.setString(++index, record.area.postalPrefix)
            statement.executeUpdate() == 1
        }

    private fun insertStatusChange(
        connection: Connection,
        record: OrderRecord.StatusChanged,
    ): Boolean =
        connection.prepareStatement(INSERT_STATUS_CHANGE).use { statement ->
            var index = 0
            statement.setString(++index, record.orderId)
            statement.setLong(++index, record.version)
            statement.setString(++index, record.status.name)
            statement.setTimestamp(++index, timestamp(record.changedAt))
            statement.executeUpdate() == 1
        }

    private fun insertCancelled(
        connection: Connection,
        record: OrderRecord.Cancelled,
    ): Boolean =
        connection.prepareStatement(INSERT_CANCELLED).use { statement ->
            var index = 0
            statement.setString(++index, record.orderId)
            statement.setTimestamp(++index, timestamp(record.cancelledAt))
            statement.setString(++index, record.reason)
            statement.executeUpdate() == 1
        }

    private fun markDirty(
        connection: Connection,
        days: Set<BusinessDay>,
        orderIds: Set<String>,
    ) {
        if (days.isNotEmpty()) {
            connection.prepareStatement(MARK_DAYS).use { statement ->
                statement.setArray(1, connection.createArrayOf("date", days.map { Date.valueOf(it.date.toJavaLocalDate()) }.toTypedArray()))
                statement.executeUpdate()
            }
        }
        if (orderIds.isNotEmpty()) {
            connection.prepareStatement(MARK_ORDERS).use { statement ->
                statement.setArray(1, connection.createArrayOf("text", orderIds.toTypedArray()))
                statement.executeUpdate()
            }
        }
    }

    public companion object {
        /** 取り込んだ位置の Watermark(日次の DAG 全体で 1 つ。パーティションをまたいで残る)。 */
        public val WATERMARK: CheckpointKey = CheckpointKey(DagId("batch-etl.order-daily"), null, "order-events.positions")

        private val INSERT_PLACED =
            """
            INSERT INTO raw.order_placed (order_id, customer_id, ordered_at, business_date, total_minor_units, currency, line_count,
                                          ship_country, ship_region, ship_postal_prefix)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (order_id) DO NOTHING
            """.trimIndent()
        private const val INSERT_STATUS_CHANGE =
            "INSERT INTO raw.order_status_change (order_id, version, status, changed_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING"
        private const val INSERT_CANCELLED =
            "INSERT INTO raw.order_cancelled (order_id, cancelled_at, reason) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
        private const val MARK_DAYS =
            "INSERT INTO etl.dirty_day (business_date) SELECT unnest(?::date[]) ON CONFLICT DO NOTHING"
        private const val MARK_ORDERS =
            "INSERT INTO etl.dirty_day (business_date) SELECT DISTINCT business_date FROM raw.order_placed " +
                "WHERE order_id = ANY(?) ON CONFLICT DO NOTHING"

        private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant.toJavaInstant())
    }
}
