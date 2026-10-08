package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.application.port.outbound.SnapshotRequests
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * signal 表に、指定した注文番号だけを取り直す Incremental Snapshot を書く(ADR-0027 §6)。
 * 接続は専用のロール `eiaf_resync`(signal 表の INSERT だけ)。Debezium のロールは使わない。
 *
 * - 指示の JSON は JSON のライブラリで組み立てる(文字列の連結をしない)。
 * - 条件(`additional-conditions` の `filter`)は Debezium が SQL としてそのまま使うので、注文番号は SQL の文字列のリテラルにし、`'` は `''` にする。
 *   注文番号は照合がレガシーから読んだ値だが、念のため印字できない文字を含むものは拒む。
 */
public class JdbcSnapshotRequests(
    private val dataSource: DataSource,
    private val table: String = TABLE,
    private val keyColumn: String = KEY_COLUMN,
) : SnapshotRequests {
    override suspend fun requestSnapshot(orderNumbers: Set<String>): Result<Unit, DomainError> {
        require(orderNumbers.isNotEmpty()) { "取り直す注文番号がありません" }
        if (orderNumbers.any { key -> key.any { it.isISOControl() } }) {
            return err(UnexpectedError("取り直す注文番号に制御文字が含まれます"))
        }
        val data = signalData(orderNumbers)
        return withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(INSERT).use { s ->
                        s.setString(1, UUID.randomUUID().toString())
                        s.setString(2, data)
                        s.executeUpdate()
                    }
                }
                ok(Unit)
            } catch (e: SQLException) {
                // 値を含みうるため、SQLState だけを出す
                if (e.sqlState?.startsWith("08") == true) {
                    err(UnavailableError("signal 表に書けません(SQLState ${e.sqlState})"))
                } else {
                    err(UnexpectedError("signal 表に書けません(SQLState ${e.sqlState})"))
                }
            }
        }
    }

    internal fun signalData(orderNumbers: Set<String>): String {
        val filter = "$keyColumn IN (" + orderNumbers.sorted().joinToString(", ") { "'" + it.replace("'", "''") + "'" } + ")"
        return Json.encodeToString(
            buildJsonObject {
                put("data-collections", JsonArray(listOf(JsonPrimitive(table))))
                put("type", "incremental")
                put(
                    "additional-conditions",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("data-collection", table)
                                put("filter", filter)
                            },
                        ),
                    ),
                )
            },
        )
    }

    public companion object {
        public const val TABLE: String = "public.t_juchu"
        public const val KEY_COLUMN: String = "col_02"
        private const val INSERT = "INSERT INTO eiaf_cdc.debezium_signal (id, type, data) VALUES (?, 'execute-snapshot', ?)"
    }
}
