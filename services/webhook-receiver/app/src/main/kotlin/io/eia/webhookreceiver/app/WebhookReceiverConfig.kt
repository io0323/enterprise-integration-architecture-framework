package io.eia.webhookreceiver.app

import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.domain.ReceiptRetention
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * webhook-receiver の設定(環境変数)。パスワードと署名の鍵は値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む
 * (署名の鍵は要求のたびに読む。再起動なしで差し替えるため。ADR-0035 §2)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `WEBHOOK_RECEIVER_DB_URL` | なし(必須) | migrate・serve |
 * | `WEBHOOK_RECEIVER_DB_USER` / `WEBHOOK_RECEIVER_DB_PASSWORD`(または `_FILE`) | `webhook_receiver` / なし | migrate だけ(所有者) |
 * | `WEBHOOK_RECEIVER_APP_DB_USER` / `WEBHOOK_RECEIVER_APP_DB_PASSWORD`(または `_FILE`) | `webhook_receiver_app` / なし | serve だけ |
 * | `WEBHOOK_RECEIVER_CDC_DB_USER` | `debezium` | migrate(Outbox の表の SELECT と DB の CONNECT を付ける Debezium のロール) |
 * | `WEBHOOK_RECEIVER_SCHEMA_REGISTRY_URL` | なし(serve では必須) | serve(例 `http://apicurio:8080/apis/registry/v3`) |
 * | `WEBHOOK_RECEIVER_HTTPS_PORT` | 8443 | serve(受け口。mTLS だけで受ける。ADR-0024 §6) |
 * | `WEBHOOK_RECEIVER_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `WEBHOOK_RECEIVER_TLS_CERT_FILE` / `_TLS_KEY_FILE` / `_TLS_CLIENT_CA_FILE` | なし(serve では必須) | serve(PEM。鍵は PKCS#8) |
 * | `WEBHOOK_RECEIVER_TLS_ALLOWED_CLIENTS` | `apisix` | serve(受け入れるクライアント証明書の SAN の DNS 名) |
 * | `WEBHOOK_RECEIVER_CRM_SECRET`(または `_FILE`) | なし(読めなければ 503) | serve(CRM と共有した署名の鍵。Standard Webhooks の `whsec_` の形も可) |
 * | `WEBHOOK_RECEIVER_CRM_SECRET_PREVIOUS`(または `_FILE`) | なし | serve(鍵の差し替えの間だけ。差し替え前の鍵) |
 * | `WEBHOOK_RECEIVER_REQUEST_BUDGET` | 5s | serve(リクエストの予算。ADR-0024 §3) |
 * | `WEBHOOK_RECEIVER_RECEIPT_RETENTION` | 30d | serve(受け取りの記録の保持期間。7 日より短くはできない) |
 * | `WEBHOOK_RECEIVER_PURGE_INTERVAL` | 1h | serve(保持期間を過ぎた記録の削除の間隔) |
 */
internal data class WebhookReceiverConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val cdcUser: String,
    val schemaRegistryUrl: String?,
    val httpsPort: Int,
    val healthPort: Int,
    val tls: TlsFiles,
    val allowedClients: Set<String>,
    val requestBudget: Duration,
    val retention: ReceiptRetention,
    val purgeInterval: Duration,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("WEBHOOK_RECEIVER_DB_PASSWORD")
        val APP_PASSWORD = SecretName("WEBHOOK_RECEIVER_APP_DB_PASSWORD")

        /** CRM の署名の鍵(差し替えの間は `_PREVIOUS` も読む)。 */
        val CRM_SECRET = SecretName("WEBHOOK_RECEIVER_CRM_SECRET")
        const val SCHEMA_REGISTRY_URL = "WEBHOOK_RECEIVER_SCHEMA_REGISTRY_URL"
        const val RETENTION = "WEBHOOK_RECEIVER_RECEIPT_RETENTION"

        /** 受け口の mTLS の環境変数(`WEBHOOK_RECEIVER_TLS_*`。ADR-0024 §6)。 */
        val TLS = TlsVariables("WEBHOOK_RECEIVER")
        private const val DEFAULT_HTTPS_PORT = 8443
        private const val DEFAULT_HEALTH_PORT = 8081
        private val DEFAULT_REQUEST_BUDGET = 5.seconds
        private val DEFAULT_PURGE_INTERVAL = 1.hours

        fun fromEnvironment(env: Map<String, String>): Result<WebhookReceiverConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                WebhookReceiverConfig(
                    dbUrl = reader.required("WEBHOOK_RECEIVER_DB_URL"),
                    ownerUser = reader.optional("WEBHOOK_RECEIVER_DB_USER") ?: "webhook_receiver",
                    appUser = reader.optional("WEBHOOK_RECEIVER_APP_DB_USER") ?: "webhook_receiver_app",
                    cdcUser = reader.optional("WEBHOOK_RECEIVER_CDC_DB_USER") ?: "debezium",
                    schemaRegistryUrl = reader.optional(SCHEMA_REGISTRY_URL),
                    httpsPort = reader.port("WEBHOOK_RECEIVER_HTTPS_PORT", DEFAULT_HTTPS_PORT),
                    healthPort = reader.port("WEBHOOK_RECEIVER_HEALTH_PORT", DEFAULT_HEALTH_PORT),
                    tls = TLS.files(env),
                    allowedClients = TLS.allowedClients(env),
                    requestBudget = reader.duration("WEBHOOK_RECEIVER_REQUEST_BUDGET") ?: DEFAULT_REQUEST_BUDGET,
                    retention = reader.retention(RETENTION),
                    purgeInterval = reader.duration("WEBHOOK_RECEIVER_PURGE_INTERVAL") ?: DEFAULT_PURGE_INTERVAL,
                )
            if (config.allowedClients.isEmpty()) reader.violation(TLS.allowedClients, "1 つ以上の名前が必要です")
            if (config.httpsPort != 0 && config.httpsPort == config.healthPort) {
                reader.violation("WEBHOOK_RECEIVER_HEALTH_PORT", "WEBHOOK_RECEIVER_HTTPS_PORT と別のポートにしてください")
            }
            return if (reader.violations.isEmpty()) ok(config) else err(ValidationError(reader.violations))
        }
    }
}

/** 環境変数を読み、誤りを [violations] に集める(最初の誤りで止めず、まとめて知らせる)。 */
private class EnvReader(
    private val env: Map<String, String>,
) {
    val violations = mutableListOf<FieldViolation>()

    fun violation(
        name: String,
        reason: String,
    ) {
        violations += FieldViolation(name, reason)
    }

    fun optional(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    fun required(name: String): String = optional(name) ?: "".also { violation(name, "必須です") }

    fun port(
        name: String,
        default: Int,
    ): Int =
        optional(name)?.let { value ->
            value.toIntOrNull()?.takeIf { it in 0..MAX_PORT } ?: default.also { violation(name, "0〜65535 のポート番号にしてください") }
        } ?: default

    fun duration(name: String): Duration? =
        optional(name)?.let { value ->
            Duration.parseOrNull(value)?.takeIf { it.isPositive() } ?: null.also { violation(name, "正の期間にしてください(例 P30D・30d)") }
        }

    fun retention(name: String): ReceiptRetention =
        duration(name)?.let { value ->
            when (val parsed = ReceiptRetention.of(value, name)) {
                is Result.Ok -> parsed.value
                is Result.Err -> ReceiptRetention.DEFAULT.also { violations += parsed.error.violations }
            }
        } ?: ReceiptRetention.DEFAULT

    private companion object {
        const val MAX_PORT = 65_535
    }
}
