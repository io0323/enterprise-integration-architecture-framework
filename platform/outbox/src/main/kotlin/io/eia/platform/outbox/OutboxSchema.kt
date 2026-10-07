package io.eia.platform.outbox

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.err
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Outbox の表(スキーマ `outbox`)と、Debezium が読む publication を作る(ADR-0007)。
 *
 * - サービスのマイグレーションとは別の履歴の表(`outbox.outbox_schema_history`)を使う。サービスの Flyway と版の番号がぶつからない
 *   (監査の `AuditSchema` と同じ)。
 * - [dataSource] は DB の所有者のロールで接続する(publication の作成には DB の CREATE の権限が要る)。
 * - [appRole] には INSERT・DELETE と、DELETE の条件に使う `id` 列の SELECT だけを付ける(UPDATE・TRUNCATE は付けない)。
 * - [cdcRole](Debezium)には SELECT だけを付ける。ロールはあらかじめ作っておく(ローカルは infra/local/postgres/init/20-debezium.sh)。
 * - publication [PUBLICATION] は Outbox の表だけを含む。Debezium は自動で作らず、これを使う(`publication.autocreate.mode=disabled`。P06 ③)。
 *
 * 方式は既定の方式(INSERT の直後に DELETE)だけ。保持期間の方式は #76。
 */
public object OutboxSchema {
    public const val SCHEMA: String = "outbox"
    public const val TABLE: String = "$SCHEMA.outbox"
    public const val PUBLICATION: String = "eiaf_outbox"
    private const val HISTORY_TABLE = "outbox_schema_history"
    private const val LOCATION = "classpath:db/outbox"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    @Suppress("ReturnCount") // 引数の検査のそれぞれで、マイグレーションの前に返す
    public fun migrate(
        dataSource: DataSource,
        appRole: String,
        cdcRole: String,
    ): Result<Unit, OutboxError> {
        if (!ROLE_NAME.matches(appRole) || !ROLE_NAME.matches(cdcRole)) {
            return err(OutboxMisuse("appRole と cdcRole は英小文字・数字・_ の 63 文字以内にしてください"))
        }
        if (appRole == cdcRole) return err(OutboxMisuse("appRole と cdcRole は別のロールにしてください"))
        return catching {
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole, "cdcRole" to cdcRole, "publication" to PUBLICATION))
                .load()
                .migrate()
            Unit
        }.mapError {
            // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、例外のクラス名だけを入れる
            OutboxStorageRejected("マイグレーションに失敗しました(${it.message})")
        }
    }
}
