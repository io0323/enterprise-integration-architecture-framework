package io.eia.order.app

import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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
 * | `ORDER_HTTPS_PORT` | 8443 | serve(API。mTLS だけで受ける。ADR-0024 §6) |
 * | `ORDER_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `ORDER_TLS_CERT_FILE` / `ORDER_TLS_KEY_FILE` | なし(serve では必須) | serve(サーバ証明書のチェーンと秘密鍵。PEM。鍵は PKCS#8) |
 * | `ORDER_TLS_CLIENT_CA_FILE` | なし(serve では必須) | serve(クライアント証明書を検証する CA。PEM) |
 * | `ORDER_TLS_ALLOWED_CLIENTS` | `apisix` | serve(受け入れるクライアント証明書の SAN の DNS 名。カンマ区切り) |
 * | `OIDC_ISSUER` / `OIDC_JWKS_URI` / `ORDER_API_AUDIENCE` | なし / なし / `order-api` | serve |
 * | `ORDER_REQUEST_BUDGET` | 10s | serve |
 * | `ORDER_IDEMPOTENCY_LEASE` | 60s | serve |
 * | `ORDER_IDEMPOTENCY_PURGE_INTERVAL` | 5m | serve |
 * | `ORDER_AUDIT_ANCHOR_ENABLED` | `true` | serve(監査のアンカーの定期的な保存。`false` は統合テストなど S3 がない環境だけ。ADR-0017 §5) |
 * | `ORDER_AUDIT_ANCHOR_INTERVAL` | 1h | serve(アンカーの検査と保存の間隔) |
 * | `ORDER_AUDIT_ANCHOR_RETENTION` | なし(有効なら serve で必須) | serve(アンカーの COMPLIANCE の保持期間。本番は監査証跡の保存期間で決める) |
 * | `ORDER_AUDIT_S3_ENDPOINT` / `ORDER_AUDIT_S3_BUCKET` | なし(有効なら serve で必須)/ `eiaf-audit` | serve |
 * | `ORDER_AUDIT_S3_ACCESS_KEY` / `ORDER_AUDIT_S3_SECRET_KEY`(または `_FILE`) | なし(有効なら serve で必須) | serve(`eiaf-audit-order`。ADR-0017 §7) |
 *
 * 期間は ISO 8601(`PT10S`)か Kotlin の表記(`10s`)で書く。
 */
internal data class OrderConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val httpsPort: Int,
    val healthPort: Int,
    val tls: TlsFiles,
    val allowedClients: Set<String>,
    val issuer: String?,
    val jwksUri: URI?,
    val audience: String,
    val requestBudget: Duration,
    val idempotencyLease: Duration,
    val purgeInterval: Duration,
    val anchor: AuditAnchorConfig,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("ORDER_DB_PASSWORD")
        val APP_PASSWORD = SecretName("ORDER_APP_DB_PASSWORD")
        private const val DEFAULT_HTTPS_PORT = 8443
        private const val DEFAULT_HEALTH_PORT = 8081

        fun fromEnvironment(env: Map<String, String>): Result<OrderConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                OrderConfig(
                    dbUrl = reader.required("ORDER_DB_URL"),
                    ownerUser = reader.optional("ORDER_DB_USER") ?: "order_service",
                    appUser = reader.optional("ORDER_APP_DB_USER") ?: "order_service_app",
                    httpsPort = reader.port("ORDER_HTTPS_PORT", DEFAULT_HTTPS_PORT),
                    healthPort = reader.port("ORDER_HEALTH_PORT", DEFAULT_HEALTH_PORT),
                    tls =
                        TlsFiles(
                            cert = reader.optional(TlsFiles.CERT),
                            key = reader.optional(TlsFiles.KEY),
                            clientCa = reader.optional(TlsFiles.CLIENT_CA),
                        ),
                    allowedClients =
                        (reader.optional("ORDER_TLS_ALLOWED_CLIENTS") ?: "apisix")
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet(),
                    issuer = reader.optional("OIDC_ISSUER"),
                    jwksUri = reader.optional("OIDC_JWKS_URI")?.let(URI::create),
                    audience = reader.optional("ORDER_API_AUDIENCE") ?: "order-api",
                    requestBudget = reader.duration("ORDER_REQUEST_BUDGET", 10.seconds),
                    idempotencyLease = reader.duration("ORDER_IDEMPOTENCY_LEASE", 60.seconds),
                    purgeInterval = reader.duration("ORDER_IDEMPOTENCY_PURGE_INTERVAL", 5.minutes),
                    anchor =
                        AuditAnchorConfig(
                            enabled = reader.boolean(AuditAnchorConfig.ENABLED, default = true),
                            interval = reader.duration("ORDER_AUDIT_ANCHOR_INTERVAL", 1.hours),
                            retention = reader.optionalDuration(AuditAnchorConfig.RETENTION),
                            endpoint = reader.optional(AuditAnchorConfig.ENDPOINT)?.let(URI::create),
                            bucket = reader.optional("ORDER_AUDIT_S3_BUCKET") ?: "eiaf-audit",
                        ),
                )
            if (config.allowedClients.isEmpty()) reader.violation("ORDER_TLS_ALLOWED_CLIENTS", "1 つ以上の名前が必要です")
            if (config.httpsPort != 0 && config.httpsPort == config.healthPort) {
                reader.violation("ORDER_HEALTH_PORT", "ORDER_HTTPS_PORT と別のポートにしてください")
            }
            if (config.requestBudget >= config.idempotencyLease) {
                reader.violation("ORDER_REQUEST_BUDGET", "冪等のリース(ORDER_IDEMPOTENCY_LEASE)より短くしてください(ADR-0024 §3)")
            }
            return reader.result(config)
        }
    }
}

/** 監査のアンカーの定期的な保存(ADR-0017 §5)。serve だけで使う。 */
internal data class AuditAnchorConfig(
    val enabled: Boolean,
    val interval: Duration,
    val retention: Duration?,
    val endpoint: URI?,
    val bucket: String,
) {
    /** 有効なときに serve で必須の値の違反。資格情報は値ではなく、[secrets] から取れるかだけを確かめる。 */
    fun serveViolations(secrets: SecretProvider): List<FieldViolation> {
        if (!enabled) return emptyList()
        val hint = "監査のアンカーの保存に必須です(無効にするなら $ENABLED=false)"
        return listOfNotNull(
            FieldViolation(ENDPOINT, hint).takeIf { endpoint == null },
            FieldViolation(RETENTION, hint).takeIf { retention == null },
        ) + listOf(ACCESS_KEY, SECRET_KEY).filter { secrets.get(it) is Result.Err }.map { FieldViolation(it.value, hint) }
    }

    companion object {
        const val ENABLED = "ORDER_AUDIT_ANCHOR_ENABLED"
        const val RETENTION = "ORDER_AUDIT_ANCHOR_RETENTION"
        const val ENDPOINT = "ORDER_AUDIT_S3_ENDPOINT"

        /** order のアンカーの書込み用の identity(`eiaf-audit-order`。ADR-0017 §7)。 */
        val ACCESS_KEY = SecretName("ORDER_AUDIT_S3_ACCESS_KEY")
        val SECRET_KEY = SecretName("ORDER_AUDIT_S3_SECRET_KEY")
    }
}

/** TLS のファイルのパス(serve で必須。migrate では使わない)。 */
internal data class TlsFiles(
    val cert: String?,
    val key: String?,
    val clientCa: String?,
) {
    companion object {
        const val CERT = "ORDER_TLS_CERT_FILE"
        const val KEY = "ORDER_TLS_KEY_FILE"
        const val CLIENT_CA = "ORDER_TLS_CLIENT_CA_FILE"
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
    ): Duration = optionalDuration(name) ?: default

    fun optionalDuration(name: String): Duration? {
        val raw = optional(name) ?: return null
        val parsed = Duration.parseOrNull(raw)?.takeIf { it.isPositive() }
        if (parsed == null) violation(name, "正の期間です(例: 10s、PT10S、P1D)")
        return parsed
    }

    fun boolean(
        name: String,
        default: Boolean,
    ): Boolean =
        when (optional(name)) {
            null -> default
            "true" -> true
            "false" -> false
            else -> default.also { violation(name, "true か false です") }
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
