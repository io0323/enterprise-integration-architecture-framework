package io.eia.shipping.app

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shipping.domain.SettledRetention
import io.eia.shipping.domain.ShippingRules
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * shipping-service の設定(環境変数)。パスワードは値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 * Kafka と Apicurio はローカルでは未認証(ADR-0008 の縮退・#26・#29)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `SHIPPING_DB_URL` | なし(必須) | migrate・serve |
 * | `SHIPPING_DB_USER` / `SHIPPING_DB_PASSWORD`(または `_FILE`) | `shipping_service` / なし | migrate だけ(所有者) |
 * | `SHIPPING_APP_DB_USER` / `SHIPPING_APP_DB_PASSWORD`(または `_FILE`) | `shipping_service_app` / なし | serve だけ(アプリのロール) |
 * | `SHIPPING_CDC_DB_USER` | `debezium` | migrate(Outbox の表の SELECT と DB の CONNECT を付ける Debezium のロール) |
 * | `SHIPPING_KAFKA_BOOTSTRAP` | なし(serve では必須) | serve(例 `kafka:9092`) |
 * | `SHIPPING_SCHEMA_REGISTRY_URL` | なし(serve では必須) | serve(例 `http://apicurio:8080/apis/registry/v3`) |
 * | `SHIPPING_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `SHIPPING_SETTLED_RETENTION` | 30d | serve(終わった出荷の記録と「取消済み」の印の保持期間。14 日より短くはできない。ADR-0029 §5) |
 * | `SHIPPING_PURGE_INTERVAL` | 1h | serve(保持期間を過ぎた記録の削除の間隔) |
 * | `SHIPPING_SUPPORTED_COUNTRIES` | JP | serve(模擬の出荷できる国。ISO 3166-1 alpha-2 のカンマ区切り。ADR-0029 §7) |
 *
 * 期間は ISO 8601(`P30D`)か Kotlin の表記(`30d`)で書く。
 */
internal data class ShippingConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val cdcUser: String,
    val bootstrapServers: String?,
    val schemaRegistryUrl: String?,
    val healthPort: Int,
    val retention: SettledRetention,
    val purgeInterval: Duration,
    val supportedCountries: Set<String> = ShippingRules.DEFAULT_COUNTRIES,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("SHIPPING_DB_PASSWORD")
        val APP_PASSWORD = SecretName("SHIPPING_APP_DB_PASSWORD")
        const val KAFKA_BOOTSTRAP = "SHIPPING_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "SHIPPING_SCHEMA_REGISTRY_URL"
        const val RETENTION = "SHIPPING_SETTLED_RETENTION"
        private const val DEFAULT_HEALTH_PORT = 8081
        private val DEFAULT_PURGE_INTERVAL = 1.hours

        fun fromEnvironment(env: Map<String, String>): Result<ShippingConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                ShippingConfig(
                    dbUrl = reader.required("SHIPPING_DB_URL"),
                    ownerUser = reader.optional("SHIPPING_DB_USER") ?: "shipping_service",
                    appUser = reader.optional("SHIPPING_APP_DB_USER") ?: "shipping_service_app",
                    cdcUser = reader.optional("SHIPPING_CDC_DB_USER") ?: "debezium",
                    bootstrapServers = reader.optional(KAFKA_BOOTSTRAP),
                    schemaRegistryUrl = reader.optional(SCHEMA_REGISTRY_URL),
                    healthPort = reader.port("SHIPPING_HEALTH_PORT", DEFAULT_HEALTH_PORT),
                    retention = reader.retention(RETENTION),
                    purgeInterval = reader.duration("SHIPPING_PURGE_INTERVAL") ?: DEFAULT_PURGE_INTERVAL,
                    supportedCountries = reader.countries("SHIPPING_SUPPORTED_COUNTRIES"),
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

    fun countries(name: String): Set<String> {
        val value = optional(name) ?: return ShippingRules.DEFAULT_COUNTRIES
        val countries =
            value
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        return if (countries.isNotEmpty() && countries.all { COUNTRY.matches(it) }) {
            countries
        } else {
            ShippingRules.DEFAULT_COUNTRIES.also { violations += FieldViolation(name, "ISO 3166-1 alpha-2 のカンマ区切りにしてください(例 JP,US)") }
        }
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
        val COUNTRY = Regex("^[A-Z]{2}$")
    }
}
