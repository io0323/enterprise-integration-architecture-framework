package io.eia.shipping.adapters.out.persistence

import io.eia.platform.inbox.InboxSchema
import io.eia.platform.outbox.OutboxSchema
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * shipping-service の DB のマイグレーション(`migrate` のサブコマンドで 1 回呼ぶ)。
 *
 * - [owner] は DB の所有者のロール(`shipping_service`)で接続する。アプリ([appRole])には必要な権限だけを付ける。
 * - 出荷の記録の表(`db/shipping`。履歴は `flyway_schema_history`)の後に、冪等消費の記録(`InboxSchema`。スキーマ `inbox`)と、
 *   Outbox(`OutboxSchema`。スキーマ `outbox`・publication `eiaf_outbox`)を適用する。どれも履歴の表が別なので、版の番号はぶつからない。
 */
public object ShippingSchema {
    public const val APP_ROLE: String = "shipping_service_app"
    public const val CDC_ROLE: String = "debezium"
    private const val LOCATION = "classpath:db/shipping"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        owner: DataSource,
        appRole: String = APP_ROLE,
        cdcRole: String = CDC_ROLE,
    ): Result<Unit, DomainError> {
        require(ROLE_NAME.matches(appRole) && ROLE_NAME.matches(cdcRole)) { "ロールは英小文字・数字・_ の 63 文字以内にしてください" }
        // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、型の名前だけを入れる
        val classify = { e: Exception -> UnexpectedError("shipping のマイグレーションに失敗しました(${e::class.simpleName})", e) }
        return catching<Unit, DomainError>(classify) {
            Flyway
                .configure()
                .dataSource(owner)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole, "cdcRole" to cdcRole))
                .load()
                .migrate()
            Unit
        }.flatMap {
            InboxSchema.migrate(owner, appRole).mapError { error -> UnexpectedError("冪等消費の記録のマイグレーションに失敗しました(${error.code})") }
        }.flatMap {
            OutboxSchema.migrate(owner, appRole, cdcRole).mapError { error -> UnexpectedError("Outbox のマイグレーションに失敗しました(${error.code})") }
        }
    }
}
