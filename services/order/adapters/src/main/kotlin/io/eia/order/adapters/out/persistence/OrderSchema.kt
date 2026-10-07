package io.eia.order.adapters.out.persistence

import io.eia.platform.audit.jdbc.AuditSchema
import io.eia.platform.outbox.OutboxSchema
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * order-service の DB のマイグレーション。起動時に 1 回呼ぶ(結線は P05 ④b)。
 *
 * - [owner] は DB の所有者のロール(`order_service`)で接続する。アプリ([appRole]。`order_service_app`)には、
 *   マイグレーションが必要な権限だけを付ける(所有者の権限は持たない。DDL・TRUNCATE はできない)。
 * - order の表(`db/order`。Flyway の履歴は `flyway_schema_history`)の後に、監査の表(`AuditSchema`。スキーマ `audit`。
 *   履歴は別の表)を同じ DB に適用する(ADR-0017)。
 * - 最後に Outbox の表と publication(`OutboxSchema`。スキーマ `outbox`。履歴は別の表)を適用する(ADR-0007)。
 *   [cdcRole](Debezium)には Outbox の表の SELECT だけを付ける。
 */
public object OrderSchema {
    public const val APP_ROLE: String = "order_service_app"
    public const val CDC_ROLE: String = "debezium"
    private const val LOCATION = "classpath:db/order"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        owner: DataSource,
        appRole: String = APP_ROLE,
        cdcRole: String = CDC_ROLE,
    ): Result<Unit, DomainError> {
        require(ROLE_NAME.matches(appRole)) { "appRole は英小文字・数字・_ の 63 文字以内にしてください" }
        // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、型の名前だけを入れる
        val classify = { e: Exception -> UnexpectedError("order のマイグレーションに失敗しました(${e::class.simpleName})", e) }
        return catching<Unit, DomainError>(classify) {
            Flyway
                .configure()
                .dataSource(owner)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole))
                .load()
                .migrate()
            Unit
        }.flatMap {
            AuditSchema.migrate(owner, appRole).mapError { error -> UnexpectedError("監査のマイグレーションに失敗しました(${error.code})") }
        }.flatMap {
            OutboxSchema.migrate(owner, appRole, cdcRole).mapError { error -> UnexpectedError("Outbox のマイグレーションに失敗しました(${error.code})") }
        }
    }
}
