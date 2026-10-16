package io.eia.fileexchange.adapters.persistence

import io.eia.platform.batch.jdbc.BatchSchema
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * file-exchange の DB のマイグレーション。`migrate` のコマンドで 1 回呼ぶ。
 *
 * - [owner] は DB の所有者のロール(`file_exchange`)で接続する。アプリ([appRole]。`file_exchange_app`)には読み書きだけを付ける。
 * - 受け取りの記録(スキーマ `exchange`。履歴は `exchange.flyway_schema_history`)の後に、ファイルの到着の実行の記録
 *   (`BatchSchema`。スキーマ `batch`。ADR-0030)を同じ DB に適用する。
 */
public object FileExchangeSchema {
    public const val APP_ROLE: String = "file_exchange_app"
    private const val LOCATION = "classpath:db/file-exchange"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        owner: DataSource,
        appRole: String = APP_ROLE,
    ): Result<Unit, DomainError> {
        require(ROLE_NAME.matches(appRole)) { "appRole は英小文字・数字・_ の 63 文字以内にしてください" }
        // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、型の名前だけを入れる
        val classify = { e: Exception -> UnexpectedError("file-exchange のマイグレーションに失敗しました(${e::class.simpleName})", e) }
        return catching<Unit, DomainError>(classify) {
            Flyway
                .configure()
                .dataSource(owner)
                .schemas("exchange")
                .createSchemas(true)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole))
                .load()
                .migrate()
            Unit
        }.flatMap {
            BatchSchema.migrate(owner, appRole).mapError { error -> UnexpectedError("実行の記録のマイグレーションに失敗しました(${error.code})") }
        }
    }
}
