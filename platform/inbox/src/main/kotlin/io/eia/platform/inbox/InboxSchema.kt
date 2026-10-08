package io.eia.platform.inbox

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.err
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * 冪等消費の記録の表(スキーマ `inbox`)を作る(ADR-0028 §3)。
 *
 * - サービスのマイグレーションとは別の履歴の表(`inbox.inbox_schema_history`)を使う。サービスの Flyway と版の番号がぶつからない
 *   (`OutboxSchema`・監査の `AuditSchema` と同じ)。
 * - [dataSource] は DB の所有者のロールで接続する。
 * - [appRole] には INSERT・DELETE と、その条件に使う列の SELECT だけを付ける(UPDATE・TRUNCATE は付けない)。
 */
public object InboxSchema {
    public const val SCHEMA: String = "inbox"
    public const val TABLE: String = "$SCHEMA.processed_message"
    private const val HISTORY_TABLE = "inbox_schema_history"
    private const val LOCATION = "classpath:db/inbox"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        dataSource: DataSource,
        appRole: String,
    ): Result<Unit, InboxError> {
        if (!ROLE_NAME.matches(appRole)) return err(InboxMisuse("appRole は英小文字・数字・_ の 63 文字以内にしてください"))
        return catching {
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole))
                .load()
                .migrate()
            Unit
        }.mapError {
            // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、例外のクラス名だけを入れる
            InboxStorageRejected("マイグレーションに失敗しました(${it.message})")
        }
    }
}
