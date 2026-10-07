package io.eia.platform.outbox

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID
import kotlin.time.toJavaInstant

/**
 * 業務の更新と同じトランザクションで、Outbox の表にイベントを書く(Framework 8.3。ADR-0007)。
 *
 * **既定の方式(ADR-0007 §2)**: 記録を INSERT し、同じトランザクションで同じ行を DELETE する。
 * - Debezium は WAL の INSERT を読んで発行する。DELETE は Outbox Event Router が発行しない(コネクタも tombstone を出さない。P06 ③)。
 * - 表に行が残らないので、表の肥大化も掃除のジョブもない。
 * - 業務がロールバックすれば、INSERT も WAL に確定しないので発行されない(二重書き込みの問題が起きない)。
 *
 * 前提: [connection] は自動コミットが無効(トランザクションの中)であること。自動コミットでは INSERT と DELETE が別々に確定し、
 * 業務の更新と同じトランザクションにならないため、[OutboxMisuse] にする。
 *
 * Exposed のトランザクションからは [appendOutbox] を使う。保持期間の方式(行を残す)は #76 で扱う。
 */
public class Outbox(
    private val listener: OutboxListener = OutboxListener.NONE,
) {
    public fun append(
        connection: Connection,
        records: List<OutboxRecord>,
    ): Result<Unit, OutboxError> {
        if (records.isEmpty()) return ok(Unit)
        val result = appendChecked(connection, records)
        when (result) {
            is Result.Ok -> listener.appended(records)
            is Result.Err -> listener.failed(result.error)
        }
        return result
    }

    private fun appendChecked(
        connection: Connection,
        records: List<OutboxRecord>,
    ): Result<Unit, OutboxError> {
        val ids = records.map { UUID.fromString(it.metadata.id.toString()) }
        if (ids.toSet().size != ids.size) return err(OutboxMisuse("同じ ID のイベントが含まれています"))
        return try {
            if (connection.autoCommit) {
                err(OutboxMisuse("Outbox にはトランザクションの中で書いてください(自動コミットが有効です)"))
            } else {
                insert(connection, records, ids)
                val deleted = delete(connection, ids)
                if (deleted == ids.size) ok(Unit) else err(OutboxStorageRejected("削除した行の数が合いません(${ids.size} 件中 $deleted 件)"))
            }
        } catch (e: SQLException) {
            err(classify(e))
        }
    }

    private fun insert(
        connection: Connection,
        records: List<OutboxRecord>,
        ids: List<UUID>,
    ) {
        connection.prepareStatement(INSERT).use { statement ->
            records.zip(ids).forEach { (record, id) ->
                val metadata = record.metadata
                var index = 0
                statement.setObject(++index, id)
                statement.setString(++index, record.topic.name)
                statement.setString(++index, record.aggregateType)
                statement.setString(++index, record.aggregateId)
                statement.setString(++index, metadata.type)
                statement.setBytes(++index, record.payload)
                statement.setString(++index, metadata.traceParent.format())
                statement.setString(++index, metadata.correlationId.value)
                statement.setObject(++index, id)
                statement.setString(++index, metadata.source)
                statement.setTimestamp(++index, Timestamp.from(metadata.time.toJavaInstant()))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun delete(
        connection: Connection,
        ids: List<UUID>,
    ): Int =
        connection.prepareStatement(DELETE).use { statement ->
            statement.setArray(1, connection.createArrayOf("uuid", ids.toTypedArray()))
            statement.executeUpdate()
        }

    private companion object {
        val INSERT =
            """
            INSERT INTO ${OutboxSchema.TABLE}
                (id, topic, aggregate_type, aggregate_id, event_type, payload, traceparent, correlation_id, ce_id, ce_source, ce_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        val DELETE = "DELETE FROM ${OutboxSchema.TABLE} WHERE id = ANY(?)"
        val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")

        /** SQLSTATE のクラスで分類する(platform/audit の SqlErrors と同じ規則)。理由には SQLSTATE だけを入れる。 */
        fun classify(e: SQLException): OutboxError {
            val state = e.sqlState.orEmpty()
            val reason = "SQLSTATE ${state.ifEmpty { "なし" }}"
            return if (state.isEmpty() ||
                state.take(2) in TRANSIENT_CLASSES
            ) {
                OutboxStorageUnavailable(reason)
            } else {
                OutboxStorageRejected(reason)
            }
        }
    }
}

/**
 * Exposed のトランザクションの中で Outbox に書く。業務の更新と同じトランザクションに入る。
 *
 * ```
 * transaction(database) {
 *     orders.insert { ... }
 *     outbox.appendOutbox(this, listOf(record))
 * }
 * ```
 */
public fun Outbox.appendOutbox(
    transaction: JdbcTransaction,
    records: List<OutboxRecord>,
): Result<Unit, OutboxError> {
    val connection = transaction.connection.connection as? Connection ?: return err(OutboxMisuse("JDBC の接続を取得できません"))
    return append(connection, records)
}
