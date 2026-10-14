@file:Suppress("MagicNumber") // JDBC のパラメータの位置

package io.eia.platform.batch.jdbc

import io.eia.platform.batch.BatchError
import io.eia.platform.batch.BatchMisuse
import io.eia.platform.batch.BatchStorageRejected
import io.eia.platform.batch.BatchStorageUnavailable
import io.eia.platform.batch.BatchStore
import io.eia.platform.batch.CheckpointKey
import io.eia.platform.batch.DagId
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.RunKey
import io.eia.platform.batch.RunLease
import io.eia.platform.batch.RunRecord
import io.eia.platform.batch.RunStatus
import io.eia.platform.batch.RunTrigger
import io.eia.platform.batch.StepName
import io.eia.platform.batch.StepRecord
import io.eia.platform.batch.StepStatus
import io.eia.platform.batch.TriggerKind
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * PostgreSQL の [BatchStore](ADR-0030 §2・§3)。表は [BatchSchema] が作る。
 *
 * - **ロック**: PostgreSQL の advisory lock(セッション)。ロックの間は接続を 1 つ持ち続け、[RunLease.close] で外して接続を返す。
 *   プロセスが落ちれば接続が切れてロックが外れるので、次の実行が再開できる。DB との接続が実行の途中で切れるとロックも外れる
 *   (ほかのインスタンスが同じ実行を始めうる。ステップは再実行安全に作り、パーティションの置換は 1 つのトランザクションにする)。
 * - **Checkpoint をステップの結果と同時に確定する**: [writeCheckpoint] の `connection` 版を、ステップの結果を書くトランザクションの中で呼ぶ
 *   (同じ DB のとき。Framework 7.4: 半端な部分コミットを残さない)。
 */
@Suppress("TooManyFunctions") // BatchStore の実装と、トランザクションの中で Checkpoint を書く関数
public class JdbcBatchStore(
    private val dataSource: DataSource,
) : BatchStore {
    override fun tryLock(run: RunKey): Result<RunLease?, BatchError> =
        sql {
            val connection = dataSource.connection
            val locked =
                try {
                    connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                        statement.setLong(1, lockId(run))
                        statement.executeQuery().use { it.next() && it.getBoolean(1) }
                    }
                } catch (e: SQLException) {
                    connection.close()
                    throw e
                }
            if (!locked) {
                connection.close()
                null
            } else {
                RunLease {
                    // 外せなくても(接続が切れていても)、接続を閉じればセッションのロックは外れる
                    connection.use { c ->
                        try {
                            c.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                                statement.setLong(1, lockId(run))
                                statement.executeQuery().close()
                            }
                        } catch (_: SQLException) {
                            // 接続ごと閉じる
                        }
                    }
                }
            }
        }

    override fun find(run: RunKey): Result<RunRecord?, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                connection.prepareStatement("$SELECT_RUN WHERE dag_id = ? AND partition_key = ?").use { statement ->
                    statement.setString(1, run.dag.value)
                    statement.setString(2, run.partition.value)
                    statement.executeQuery().use { rs -> if (rs.next()) runRecord(rs) else null }
                }
            }
        }

    override fun begin(
        run: RunKey,
        trigger: RunTrigger,
        runTraceparent: String?,
        restart: Boolean,
        now: Instant,
    ): Result<RunRecord, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                transaction(connection) {
                    if (restart) {
                        connection.update("DELETE FROM $STEP WHERE dag_id = ? AND partition_key = ?", run.dag.value, run.partition.value)
                        connection.update(
                            "DELETE FROM $CHECKPOINT WHERE dag_id = ? AND partition_key = ?",
                            run.dag.value,
                            run.partition.value,
                        )
                    }
                    connection.prepareStatement(if (restart) RESTART else INSERT_OR_RESUME).use { statement ->
                        val attributes = json.encodeToString(ATTRIBUTES, trigger.attributes)
                        val values =
                            listOf<Any?>(
                                trigger.kind.name,
                                trigger.traceparent,
                                trigger.correlationId,
                                attributes,
                                runTraceparent,
                                timestamp(now),
                            )
                        val key = listOf<Any?>(run.dag.value, run.partition.value)
                        // INSERT はキーが先、UPDATE(やり直し)はキーが WHERE で後
                        bind(statement, (if (restart) values + key else key + values).toTypedArray())
                        statement.executeQuery().use { rs ->
                            rs.next()
                            runRecord(rs)
                        }
                    }
                }
            }
        }

    override fun steps(run: RunKey): Result<Map<StepName, StepRecord>, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT step_name, status, attempts, started_at, finished_at FROM $STEP WHERE dag_id = ? AND partition_key = ?",
                    ).use { statement ->
                        statement.setString(1, run.dag.value)
                        statement.setString(2, run.partition.value)
                        statement.executeQuery().use { rs ->
                            buildMap {
                                while (rs.next()) {
                                    val name = StepName(rs.getString("step_name"))
                                    put(
                                        name,
                                        StepRecord(
                                            name,
                                            StepStatus.valueOf(rs.getString("status")),
                                            rs.getInt("attempts"),
                                            instant(rs, "started_at")!!,
                                            instant(rs, "finished_at"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            }
        }

    override fun stepStarted(
        run: RunKey,
        step: StepName,
        attempt: Int,
        now: Instant,
    ): Result<Unit, BatchError> {
        if (attempt < 1) return err(BatchMisuse("attempt は 1 以上にしてください: $attempt"))
        return sql {
            dataSource.connection.use { connection ->
                connection.update(STEP_STARTED, run.dag.value, run.partition.value, step.value, attempt, timestamp(now))
            }
            Unit
        }
    }

    override fun stepFinished(
        run: RunKey,
        step: StepName,
        status: StepStatus,
        now: Instant,
    ): Result<Unit, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                connection.update(
                    "UPDATE $STEP SET status = ?, finished_at = ? WHERE dag_id = ? AND partition_key = ? AND step_name = ?",
                    status.name,
                    timestamp(now),
                    run.dag.value,
                    run.partition.value,
                    step.value,
                )
            }
            Unit
        }

    override fun finish(
        run: RunKey,
        status: RunStatus,
        now: Instant,
    ): Result<Unit, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                connection.update(
                    "UPDATE $RUN SET status = ?, finished_at = ? WHERE dag_id = ? AND partition_key = ?",
                    status.name,
                    timestamp(now),
                    run.dag.value,
                    run.partition.value,
                )
            }
            Unit
        }

    override fun readCheckpoint(key: CheckpointKey): Result<String?, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement("SELECT value FROM $CHECKPOINT WHERE dag_id = ? AND partition_key = ? AND name = ?")
                    .use { statement ->
                        statement.setString(1, key.dag.value)
                        statement.setString(2, key.partition?.value.orEmpty())
                        statement.setString(3, key.name)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
            }
        }

    override fun writeCheckpoint(
        key: CheckpointKey,
        value: String,
        now: Instant,
    ): Result<Unit, BatchError> =
        sql {
            dataSource.connection.use { connection -> upsertCheckpoint(connection, key, value, now) }
        }

    /**
     * Checkpoint を [connection] のトランザクションの中で書く(ステップの結果と同時に確定する)。
     * 前提: [connection] は自動コミットが無効で、このストアと同じ DB であること。自動コミットでは結果と別々に確定するため [BatchMisuse] にする。
     */
    public fun writeCheckpoint(
        connection: Connection,
        key: CheckpointKey,
        value: String,
        now: Instant,
    ): Result<Unit, BatchError> =
        when {
            // 自動コミットの問い合わせも失敗しうるので、sql の中で確かめる
            sql { connection.autoCommit }.let { it is Result.Ok && it.value } -> {
                err(BatchMisuse("Checkpoint はステップの結果と同じトランザクションで書いてください(自動コミットが有効です)"))
            }

            else -> {
                sql { upsertCheckpoint(connection, key, value, now) }
            }
        }

    override fun recentDurations(
        dag: DagId,
        step: StepName,
        limit: Int,
    ): Result<List<Duration>, BatchError> {
        if (limit < 1) return err(BatchMisuse("limit は 1 以上にしてください: $limit"))
        return sql {
            dataSource.connection.use { connection ->
                connection.prepareStatement(RECENT_DURATIONS).use { statement ->
                    statement.setString(1, dag.value)
                    statement.setString(2, step.value)
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getLong(1).milliseconds) }
                    }
                }
            }
        }
    }

    override fun runs(
        dag: DagId,
        status: RunStatus,
        since: Instant,
    ): Result<List<RunRecord>, BatchError> =
        sql {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "$SELECT_RUN WHERE dag_id = ? AND status = ? AND coalesce(finished_at, started_at) >= ? ORDER BY partition_key",
                    ).use { statement ->
                        statement.setString(1, dag.value)
                        statement.setString(2, status.name)
                        statement.setObject(3, timestamp(since))
                        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(runRecord(rs)) } }
                    }
            }
        }

    private fun upsertCheckpoint(
        connection: Connection,
        key: CheckpointKey,
        value: String,
        now: Instant,
    ) {
        connection.update(UPSERT_CHECKPOINT, key.dag.value, key.partition?.value.orEmpty(), key.name, value, timestamp(now))
    }

    private companion object {
        const val RUN = "${BatchSchema.SCHEMA}.batch_run"
        const val STEP = "${BatchSchema.SCHEMA}.batch_step"
        const val CHECKPOINT = "${BatchSchema.SCHEMA}.batch_checkpoint"
        const val RUN_COLUMNS =
            "dag_id, partition_key, status, generation, trigger_kind, trigger_traceparent, correlation_id, " +
                "attributes::text AS attributes, run_traceparent, started_at, finished_at"
        const val SELECT_RUN = "SELECT $RUN_COLUMNS FROM $RUN"
        val INSERT_OR_RESUME =
            """
            INSERT INTO $RUN (dag_id, partition_key, status, generation, trigger_kind, trigger_traceparent, correlation_id,
                                   attributes, run_traceparent, started_at)
            VALUES (?, ?, 'RUNNING', 1, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (dag_id, partition_key) DO UPDATE
                SET status = 'RUNNING', run_traceparent = EXCLUDED.run_traceparent, finished_at = NULL
            RETURNING $RUN_COLUMNS
            """.trimIndent()
        val RESTART =
            """
            UPDATE $RUN
                SET status = 'RUNNING', generation = generation + 1, trigger_kind = ?, trigger_traceparent = ?,
                    correlation_id = ?, attributes = ?::jsonb, run_traceparent = ?, started_at = ?, finished_at = NULL
            WHERE dag_id = ? AND partition_key = ?
            RETURNING $RUN_COLUMNS
            """.trimIndent()
        val STEP_STARTED =
            """
            INSERT INTO $STEP AS s (dag_id, partition_key, step_name, status, attempts, started_at)
            VALUES (?, ?, ?, 'RUNNING', ?, ?)
            ON CONFLICT (dag_id, partition_key, step_name) DO UPDATE
                SET status = 'RUNNING', attempts = EXCLUDED.attempts, finished_at = NULL,
                    started_at = CASE WHEN EXCLUDED.attempts = 1 THEN EXCLUDED.started_at ELSE s.started_at END
            """.trimIndent()
        val RECENT_DURATIONS =
            """
            SELECT (extract(epoch FROM finished_at - started_at) * 1000)::bigint FROM $STEP
            WHERE dag_id = ? AND step_name = ? AND status = 'SUCCEEDED' AND finished_at IS NOT NULL
            ORDER BY finished_at DESC
            LIMIT ?
            """.trimIndent()
        val UPSERT_CHECKPOINT =
            """
            INSERT INTO $CHECKPOINT (dag_id, partition_key, name, value, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (dag_id, partition_key, name) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
            """.trimIndent()
        val ATTRIBUTES = MapSerializer(String.serializer(), String.serializer())
        val json = Json
        private val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")
        private const val LOCK_NAMESPACE = "eiaf.batch:"

        /** advisory lock の ID。実行のキーの SHA-256 の先頭 8 バイト(ほかの用途の advisory lock とぶつかりにくくする)。 */
        fun lockId(run: RunKey): Long =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest("$LOCK_NAMESPACE$run".toByteArray())).getLong()

        fun timestamp(instant: Instant): OffsetDateTime = instant.toJavaInstant().atOffset(ZoneOffset.UTC)

        fun instant(
            rs: ResultSet,
            column: String,
        ): Instant? = rs.getObject(column, OffsetDateTime::class.java)?.toInstant()?.toKotlinInstant()

        fun runRecord(rs: ResultSet): RunRecord =
            RunRecord(
                key = RunKey(DagId(rs.getString("dag_id")), PartitionKey(rs.getString("partition_key"))),
                status = RunStatus.valueOf(rs.getString("status")),
                generation = rs.getInt("generation"),
                trigger =
                    RunTrigger(
                        kind = TriggerKind.valueOf(rs.getString("trigger_kind")),
                        traceparent = rs.getString("trigger_traceparent"),
                        correlationId = rs.getString("correlation_id"),
                        attributes = json.decodeFromString(ATTRIBUTES, rs.getString("attributes")),
                    ),
                runTraceparent = rs.getString("run_traceparent"),
                startedAt = instant(rs, "started_at")!!,
                finishedAt = instant(rs, "finished_at"),
            )

        fun Connection.update(
            sql: String,
            vararg parameters: Any?,
        ): Int =
            prepareStatement(sql).use { statement ->
                bind(statement, parameters)
                statement.executeUpdate()
            }

        fun bind(
            statement: PreparedStatement,
            parameters: Array<out Any?>,
        ) {
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        }

        inline fun <T> transaction(
            connection: Connection,
            block: () -> T,
        ): T {
            connection.autoCommit = false
            try {
                return block().also { connection.commit() }
            } catch (e: SQLException) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }

        /** SQL の例外を [BatchError] に変換する(platform/inbox と同じ規則)。理由には SQLSTATE だけを入れる。 */
        inline fun <T> sql(block: () -> T): Result<T, BatchError> =
            try {
                ok(block())
            } catch (e: SQLException) {
                val state = e.sqlState.orEmpty()
                val reason = "SQLSTATE ${state.ifEmpty { "なし" }}"
                err(
                    if (state.isEmpty() ||
                        state.take(2) in TRANSIENT_CLASSES
                    ) {
                        BatchStorageUnavailable(reason)
                    } else {
                        BatchStorageRejected(reason)
                    },
                )
            }
    }
}
