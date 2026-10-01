package io.eia.order.app

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * order-service の設定(環境変数。ADR-0024 §5)。パスワードは値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `ORDER_DB_URL` | なし(必須) | migrate・serve |
 * | `ORDER_DB_USER` / `ORDER_DB_PASSWORD`(または `_FILE`) | `order_service` / なし | migrate だけ(所有者) |
 * | `ORDER_APP_DB_USER` / `ORDER_APP_DB_PASSWORD`(または `_FILE`) | `order_service_app` / なし | serve だけ(アプリのロール) |
 * | `ORDER_HTTP_PORT` | 8080 | serve |
 * | `OIDC_ISSUER` / `OIDC_JWKS_URI` / `ORDER_API_AUDIENCE` | なし / なし / `order-api` | serve |
 * | `ORDER_REQUEST_BUDGET` | 10s | serve |
 * | `ORDER_IDEMPOTENCY_LEASE` | 60s | serve |
 * | `ORDER_IDEMPOTENCY_PURGE_INTERVAL` | 5m | serve |
 *
 * 期間は ISO 8601(`PT10S`)か Kotlin の表記(`10s`)で書く。
 */
internal data class OrderConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val httpPort: Int,
    val issuer: String?,
    val jwksUri: URI?,
    val audience: String,
    val requestBudget: Duration,
    val idempotencyLease: Duration,
    val purgeInterval: Duration,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("ORDER_DB_PASSWORD")
        val APP_PASSWORD = SecretName("ORDER_APP_DB_PASSWORD")
        private const val DEFAULT_PORT = 8080

        fun fromEnvironment(env: Map<String, String>): Result<OrderConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                OrderConfig(
                    dbUrl = reader.required("ORDER_DB_URL"),
                    ownerUser = reader.optional("ORDER_DB_USER") ?: "order_service",
                    appUser = reader.optional("ORDER_APP_DB_USER") ?: "order_service_app",
                    httpPort = reader.port("ORDER_HTTP_PORT", DEFAULT_PORT),
                    issuer = reader.optional("OIDC_ISSUER"),
                    jwksUri = reader.optional("OIDC_JWKS_URI")?.let(URI::create),
                    audience = reader.optional("ORDER_API_AUDIENCE") ?: "order-api",
                    requestBudget = reader.duration("ORDER_REQUEST_BUDGET", 10.seconds),
                    idempotencyLease = reader.duration("ORDER_IDEMPOTENCY_LEASE", 60.seconds),
                    purgeInterval = reader.duration("ORDER_IDEMPOTENCY_PURGE_INTERVAL", 5.minutes),
                )
            if (config.requestBudget >= config.idempotencyLease) {
                reader.violation("ORDER_REQUEST_BUDGET", "冪等のリース(ORDER_IDEMPOTENCY_LEASE)より短くしてください(ADR-0024 §3)")
            }
            return reader.result(config)
        }
    }
}

/** 環境変数を読み、違反を集める。 */
private class EnvReader(
    private val env: Map<String, String>,
) {
    private val violations = mutableListOf<FieldViolation>()

    fun optional(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    fun required(name: String): String = optional(name) ?: "".also { violation(name, "必須です") }

    fun port(
        name: String,
        default: Int,
    ): Int {
        val port = optional(name)?.toIntOrNull() ?: default
        if (port !in 0..MAX_PORT) violation(name, "0〜$MAX_PORT です")
        return port
    }

    fun duration(
        name: String,
        default: Duration,
    ): Duration {
        val raw = optional(name) ?: return default
        val parsed = Duration.parseOrNull(raw)?.takeIf { it.isPositive() }
        if (parsed == null) violation(name, "正の期間です(例: 10s、PT10S)")
        return parsed ?: default
    }

    fun violation(
        name: String,
        reason: String,
    ) {
        violations += FieldViolation(name, reason)
    }

    fun <T> result(value: T): Result<T, ValidationError> =
        if (violations.isEmpty()) ok(value) else err(ValidationError(violations.toList()))

    private companion object {
        const val MAX_PORT = 65_535
    }
}
