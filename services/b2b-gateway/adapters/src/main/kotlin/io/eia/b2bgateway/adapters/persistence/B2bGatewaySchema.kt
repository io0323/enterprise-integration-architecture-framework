package io.eia.b2bgateway.adapters.persistence

import io.eia.platform.audit.jdbc.AuditSchema
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * b2b-gateway の DB のマイグレーション。`migrate` のコマンドで 1 回呼ぶ(P12 ⑥)。
 *
 * - [owner] は DB の所有者のロール(`b2b_gateway`)で接続する。アプリ([appRole]。`b2b_gateway_app`)には読み書きだけを付ける
 *   (受け取りの記録と注文は INSERT と SELECT だけ。消さない・書き換えない)。
 * - 受け取りの記録(スキーマ `b2b`。履歴は `b2b.flyway_schema_history`)の後に、監査の表(`AuditSchema`。スキーマ `audit`。ADR-0017)を適用する。
 */
public object B2bGatewaySchema {
    public const val APP_ROLE: String = "b2b_gateway_app"
    private const val LOCATION = "classpath:db/b2b-gateway"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        owner: DataSource,
        appRole: String = APP_ROLE,
    ): Result<Unit, DomainError> {
        require(ROLE_NAME.matches(appRole)) { "appRole は英小文字・数字・_ の 63 文字以内にしてください" }
        // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、型の名前だけを入れる
        val classify = { e: Exception -> UnexpectedError("b2b-gateway のマイグレーションに失敗しました(${e::class.simpleName})", e) }
        return catching<Unit, DomainError>(classify) {
            Flyway
                .configure()
                .dataSource(owner)
                .schemas("b2b")
                .createSchemas(true)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole))
                .load()
                .migrate()
            Unit
        }.flatMap {
            AuditSchema.migrate(owner, appRole).mapError { error -> UnexpectedError("監査のマイグレーションに失敗しました(${error.code})") }
        }
    }
}
