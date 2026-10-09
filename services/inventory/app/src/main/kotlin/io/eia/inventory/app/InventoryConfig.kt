package io.eia.inventory.app

import io.eia.inventory.domain.SettledRetention
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * inventory-service の設定(環境変数)。パスワードは値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 * Kafka と Apicurio はローカルでは未認証(ADR-0008 の縮退・#26・#29)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `INVENTORY_DB_URL` | なし(必須) | migrate・serve |
 * | `INVENTORY_DB_USER` / `INVENTORY_DB_PASSWORD`(または `_FILE`) | `inventory_service` / なし | migrate だけ(所有者) |
 * | `INVENTORY_APP_DB_USER` / `INVENTORY_APP_DB_PASSWORD`(または `_FILE`) | `inventory_service_app` / なし | serve だけ(アプリのロール) |
 * | `INVENTORY_CDC_DB_USER` | `debezium` | migrate(Outbox の表の SELECT と DB の CONNECT を付ける Debezium のロール) |
 * | `INVENTORY_KAFKA_BOOTSTRAP` | なし(serve では必須) | serve(例 `kafka:9092`) |
 * | `INVENTORY_SCHEMA_REGISTRY_URL` | なし(serve では必須) | serve(例 `http://apicurio:8080/apis/registry/v3`) |
 * | `INVENTORY_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `INVENTORY_SETTLED_RETENTION` | 30d | serve(終わった引当の記録と「取消済み」の印の保持期間。14 日より短くはできない。ADR-0029 §5) |
 * | `INVENTORY_PURGE_INTERVAL` | 1h | serve(保持期間を過ぎた記録の削除の間隔) |
 *
 * 期間は ISO 8601(`P30D`)か Kotlin の表記(`30d`)で書く。
 */
internal data class InventoryConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val cdcUser: String,
    val bootstrapServers: String?,
    val schemaRegistryUrl: String?,
    val healthPort: Int,
    val retention: SettledRetention,
    val purgeInterval: Duration,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("INVENTORY_DB_PASSWORD")
        val APP_PASSWORD = SecretName("INVENTORY_APP_DB_PASSWORD")
        const val KAFKA_BOOTSTRAP = "INVENTORY_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "INVENTORY_SCHEMA_REGISTRY_URL"
        const val RETENTION = "INVENTORY_SETTLED_RETENTION"
        private const val DEFAULT_HEALTH_PORT = 8081
        private val DEFAULT_PURGE_INTERVAL = 1.hours

        fun fromEnvironment(env: Map<String, String>): Result<InventoryConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                InventoryConfig(
                    dbUrl = reader.required("INVENTORY_DB_URL"),
                    ownerUser = reader.optional("INVENTORY_DB_USER") ?: "inventory_service",
                    appUser = reader.optional("INVENTORY_APP_DB_USER") ?: "inventory_service_app",
                    cdcUser = reader.optional("INVENTORY_CDC_DB_USER") ?: "debezium",
                    bootstrapServers = reader.optional(KAFKA_BOOTSTRAP),
                    schemaRegistryUrl = reader.optional(SCHEMA_REGISTRY_URL),
                    healthPort = reader.port("INVENTORY_HEALTH_PORT", DEFAULT_HEALTH_PORT),
                    retention = reader.retention(RETENTION),
                    purgeInterval = reader.duration("INVENTORY_PURGE_INTERVAL") ?: DEFAULT_PURGE_INTERVAL,
                )
            return if (reader.violations.isEmpty()) ok(config) else err(ValidationError(reader.violations))
        }
    }

    /** serve に必要な設定(Kafka と Apicurio)がそろっているか。 */
    fun forServe(): Result<ServeSettings, ValidationError> {
        val violations =
            buildList {
                if (bootstrapServers == null) add(FieldViolation(KAFKA_BOOTSTRAP, "serve では必須です"))
                if (schemaRegistryUrl == null) add(FieldViolation(SCHEMA_REGISTRY_URL, "serve では必須です"))
            }
        return if (violations.isEmpty()) {
            ok(
                ServeSettings(checkNotNull(bootstrapServers), checkNotNull(schemaRegistryUrl)),
            )
        } else {
            err(ValidationError(violations))
        }
    }
}

internal data class ServeSettings(
    val bootstrapServers: String,
    val schemaRegistryUrl: String,
)

/** 環境変数を読み、誤りを [violations] に集める(最初の誤りで止めず、まとめて知らせる)。 */
private class EnvReader(
    private val env: Map<String, String>,
) {
    val violations = mutableListOf<FieldViolation>()

    fun optional(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    fun required(name: String): String = optional(name) ?: "".also { violations += FieldViolation(name, "必須です") }

    fun port(
        name: String,
        default: Int,
    ): Int =
        optional(name)?.let { value ->
            value.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                ?: default.also { violations += FieldViolation(name, "0〜65535 のポート番号にしてください") }
        } ?: default

    fun duration(name: String): Duration? =
        optional(name)?.let { value ->
            Duration.parseOrNull(value)?.takeIf { it.isPositive() }
                ?: null.also { violations += FieldViolation(name, "正の期間にしてください(例 P30D・30d)") }
        }

    fun retention(name: String): SettledRetention =
        duration(name)?.let { value ->
            when (val parsed = SettledRetention.of(value, name)) {
                is Result.Ok -> parsed.value
                is Result.Err -> SettledRetention.DEFAULT.also { violations += parsed.error.violations }
            }
        } ?: SettledRetention.DEFAULT

    private companion object {
        const val MAX_PORT = 65_535
    }
}
