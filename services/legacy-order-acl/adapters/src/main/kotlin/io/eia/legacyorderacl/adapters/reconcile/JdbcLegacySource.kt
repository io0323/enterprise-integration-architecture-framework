package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.application.port.outbound.LegacySource
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.application.port.outbound.SourceSnapshot
import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientException
import javax.sql.DataSource
import kotlin.time.TimeSource

/**
 * レガシーの受注表を、照合のために読む(読み取り専用のロール `eiaf_reconcile`。ADR-0027)。
 *
 * - `REPEATABLE READ`・読み取り専用のトランザクションで、最初の文で位置(LSN)を取り、同じスナップショットで行を読む。
 *   スナップショットで見えるトランザクションは、すべてその位置より前にコミットしている。
 * - **読み終えたら、すぐにコミットして接続を閉じてから戻る**(取り込み・処理の待ちの間に古いスナップショットを残さない。
 *   残すと、レガシーの DB の VACUUM が止まる)。
 * - 位置は、レプリカ(本番の読み取り先)では最後に適用した位置(`pg_last_wal_replay_lsn`)、プライマリでは `pg_current_wal_lsn`。
 * - 列は、Debezium の生の CDC と同じ形にする(NUMERIC は文字列、タイムゾーンのない時刻は現地時刻を UTC とみなしたマイクロ秒)。
 *
 * @param slotSource レプリケーションスロットの位置を読む接続先(スロットはプライマリにだけある。ローカルは [dataSource] と同じ)
 */
public class JdbcLegacySource(
    private val dataSource: DataSource,
    private val slotSource: DataSource = dataSource,
    private val slotName: String = DEFAULT_SLOT,
    private val waits: ReconcileWaits = ReconcileWaits(),
) : LegacySource {
    override suspend fun read(scope: ReconcileScope): Result<SourceSnapshot, DomainError> =
        jdbc(dataSource) { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            try {
                val position =
                    connection.createStatement().use { s ->
                        s.executeQuery(POSITION).use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                val rows = rows(connection, scope)
                connection.commit()
                SourceSnapshot(position, rows)
            } catch (e: SQLException) {
                connection.rollback()
                throw e
            }
        }

    override suspend fun awaitCaptured(position: String): Result<Unit, DomainError> {
        val deadline = TimeSource.Monotonic.markNow() + waits.timeout
        var result: Result<Unit, DomainError>? = null
        while (result == null) {
            result =
                when (val captured = jdbc(slotSource) { capturedPast(it, position) }) {
                    is Result.Err -> {
                        captured
                    }

                    is Result.Ok if captured.value -> {
                        ok(Unit)
                    }

                    else if deadline.hasPassedNow() -> {
                        err(UnavailableError("レプリケーションスロット $slotName が $position まで取り込みません(${waits.timeout} を超えた)"))
                    }

                    else -> {
                        null.also { delay(waits.poll) }
                    }
                }
        }
        return result
    }

    private fun rows(
        connection: Connection,
        scope: ReconcileScope,
    ): List<LegacyOrderRow> {
        val sql = if (scope is ReconcileScope.Keys) "$ROWS WHERE col_02 = ANY(?)" else ROWS
        return connection.prepareStatement(sql).use { s ->
            if (scope is ReconcileScope.Keys) s.setArray(1, connection.createArrayOf("bpchar", scope.orderNumbers.toTypedArray()))
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LegacyOrderRow(
                                orderNumber = rs.getString("col_02"),
                                statusCode = rs.getString("col_03"),
                                customerName = rs.getString("col_04"),
                                customerCode = rs.getString("col_05"),
                                amount = rs.getString("col_06"),
                                orderedAtLocalMicros = rs.getLong("col_07"),
                                updatedAtLocalMicros = rs.getLong("col_08"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun capturedPast(
        connection: Connection,
        position: String,
    ): Boolean =
        connection.prepareStatement(CAPTURED).use { s ->
            s.setString(1, position)
            s.setString(2, slotName)
            s.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    private suspend fun <T> jdbc(
        source: DataSource,
        block: (Connection) -> T,
    ): Result<T, DomainError> =
        withContext(Dispatchers.IO) {
            try {
                ok(source.connection.use(block))
            } catch (e: SQLTransientException) {
                err(UnavailableError("レガシーの DB に接続できません(SQLState ${e.sqlState})"))
            } catch (e: SQLException) {
                // 接続の失敗(08xxx)・タイムアウト(57014 = statement_timeout)は一時的。メッセージは値を含みうるので SQLState だけ
                if (e.sqlState?.startsWith("08") == true || e.sqlState == STATEMENT_TIMEOUT) {
                    err(UnavailableError("レガシーの DB の読み取りに失敗しました(SQLState ${e.sqlState})"))
                } else {
                    err(UnexpectedError("レガシーの DB の読み取りに失敗しました(SQLState ${e.sqlState})"))
                }
            }
        }

    public companion object {
        public const val DEFAULT_SLOT: String = "legacy_juchu"
        private const val STATEMENT_TIMEOUT = "57014"
        private const val POSITION =
            "SELECT (CASE WHEN pg_is_in_recovery() THEN pg_last_wal_replay_lsn() ELSE pg_current_wal_lsn() END)::text"

        // Debezium の生の CDC と同じ形: NUMERIC は文字列、タイムゾーンのない時刻はエポック(UTC とみなす)からのマイクロ秒
        private const val ROWS =
            "SELECT col_02, col_03, col_04, col_05, col_06::text AS col_06, " +
                "(extract(epoch FROM col_07) * 1000000)::bigint AS col_07, (extract(epoch FROM col_08) * 1000000)::bigint AS col_08 " +
                "FROM t_juchu"
        private const val CAPTURED =
            "SELECT confirmed_flush_lsn IS NOT NULL AND confirmed_flush_lsn >= ?::pg_lsn FROM pg_replication_slots WHERE slot_name = ?"
    }
}
