package io.eia.webhookreceiver.adapters.out.persistence

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
 * webhook-receiver の DB のマイグレーション(`migrate` のサブコマンドで 1 回呼ぶ)。
 *
 * - [owner] は DB の所有者のロール(`webhook_receiver`)で接続する。アプリ([appRole])には必要な権限だけを付ける。
 * - 受け取りの記録の表(`db/webhook-receiver`)の後に、Outbox(`OutboxSchema`。スキーマ `outbox`・publication `eiaf_outbox`)を適用する。
 */
public object WebhookReceiverSchema {
    public const val APP_ROLE: String = "webhook_receiver_app"
    public const val CDC_ROLE: String = "debezium"
    private const val LOCATION = "classpath:db/webhook-receiver"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        owner: DataSource,
        appRole: String = APP_ROLE,
        cdcRole: String = CDC_ROLE,
    ): Result<Unit, DomainError> {
        require(ROLE_NAME.matches(appRole) && ROLE_NAME.matches(cdcRole)) { "ロールは英小文字・数字・_ の 63 文字以内にしてください" }
        // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、型の名前だけを入れる
        val classify = { e: Exception -> UnexpectedError("webhook-receiver のマイグレーションに失敗しました(${e::class.simpleName})", e) }
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
            OutboxSchema.migrate(owner, appRole, cdcRole).mapError { error -> UnexpectedError("Outbox のマイグレーションに失敗しました(${error.code})") }
        }
    }
}
