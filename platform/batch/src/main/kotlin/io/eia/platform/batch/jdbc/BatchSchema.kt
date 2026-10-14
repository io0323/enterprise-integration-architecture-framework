package io.eia.platform.batch.jdbc

import io.eia.platform.batch.BatchError
import io.eia.platform.batch.BatchMisuse
import io.eia.platform.batch.BatchStorageRejected
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.err
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * バッチの実行の記録の表(スキーマ `batch`)を作る(ADR-0030 §3)。
 *
 * - バッチのマイグレーションとは別の履歴の表(`batch.batch_schema_history`)を使う(`InboxSchema`・`OutboxSchema` と同じ)。
 * - [dataSource] は DB の所有者のロールで接続する。
 * - [appRole] には読み書き(SELECT・INSERT・UPDATE・DELETE)だけを付ける。
 */
public object BatchSchema {
    public const val SCHEMA: String = "batch"
    private const val HISTORY_TABLE = "batch_schema_history"
    private const val LOCATION = "classpath:db/batch"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        dataSource: DataSource,
        appRole: String,
    ): Result<Unit, BatchError> {
        if (!ROLE_NAME.matches(appRole)) return err(BatchMisuse("appRole は英小文字・数字・_ の 63 文字以内にしてください"))
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
            BatchStorageRejected("マイグレーションに失敗しました(${it.message})")
        }
    }
}
