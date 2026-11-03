package io.eia.bffgraphql.app

import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * bff-graphql の設定(環境変数)。クライアントの秘密は値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 *
 * | 環境変数 | 既定 | 内容 |
 * |---|---|---|
 * | `BFF_HTTPS_PORT` / `BFF_HEALTH_PORT` | 8443 / 8081 | GraphQL(mTLS だけ)/ `/health/live` と `/health/ready`(平文。コンテナの外に公開しない) |
 * | `BFF_TLS_CERT_FILE` / `BFF_TLS_KEY_FILE` / `BFF_TLS_CLIENT_CA_FILE` | なし(必須) | サーバ証明書。下流の gRPC のクライアント証明書にも使う(SAN は `bff-graphql`) |
 * | `BFF_TLS_ALLOWED_CLIENTS` | `apisix` | 受け入れるクライアント証明書の SAN(Gateway だけ) |
 * | `BFF_AUDIENCE` | `bff-graphql` | 受け取るトークンの `aud` |
 * | `OIDC_ISSUER` / `OIDC_JWKS_URI` | なし(必須) | トークンの検証(ADR-0019) |
 * | `BFF_TOKEN_URL` | なし(必須) | 下流を呼ぶトークンの取得先(Client Credentials) |
 * | `BFF_CLIENT_ID` | `eiaf-bff-graphql` | 下流を呼ぶクライアント(読み取りのスコープだけ。ADR-0043 §7) |
 * | `BFF_CLIENT_SECRET`(または `_FILE`) | なし(必須) | そのクライアントの秘密 |
 * | `BFF_GATEWAY_URL` | なし(必須) | 注文を読む API Gateway(ADR-0043 §9) |
 * | `BFF_INVENTORY_TARGET` / `BFF_SHIPPING_TARGET` | なし(必須) | 在庫・出荷の gRPC(例 `inventory-service:9443`) |
 * | `BFF_REQUEST_BUDGET` | 5s | 1 回のリクエストの予算(ADR-0043 §6)。Gateway のタイムアウトより短くする |
 * | `BFF_INTROSPECTION_ENABLED` | `false` | イントロスペクション。本番では有効にしない(ADR-0043 §4) |
 * | `BFF_COMPLEXITY_BUCKET_CAPACITY` | 1000 | 複雑度のトークンバケットの大きさ(クライアントごと。ADR-0043 §9) |
 * | `BFF_COMPLEXITY_REFILL_PER_SECOND` | 60 | 1 秒に戻す複雑度 |
 *
 * 期間は ISO 8601(`PT5S`)か Kotlin の表記(`5s`)で書く。
 */
internal data class BffConfig(
    val httpsPort: Int,
    val healthPort: Int,
    val tls: TlsFiles,
    val allowedClients: Set<String>,
    val audience: String,
    val issuer: String,
    val jwksUri: URI,
    val tokenUrl: URI,
    val clientId: String,
    val gatewayUrl: URI,
    val inventoryTarget: String,
    val shippingTarget: String,
    val requestBudget: Duration,
    val introspectionEnabled: Boolean,
    val complexityCapacity: Int,
    val complexityRefillPerSecond: Int,
) {
    companion object {
        val CLIENT_SECRET = SecretName("BFF_CLIENT_SECRET")
        val TLS = TlsVariables("BFF")
        const val INTROSPECTION_ENABLED = "BFF_INTROSPECTION_ENABLED"
        const val COMPLEXITY_CAPACITY = "BFF_COMPLEXITY_BUCKET_CAPACITY"
        const val COMPLEXITY_REFILL = "BFF_COMPLEXITY_REFILL_PER_SECOND"
        private const val DEFAULT_HTTPS_PORT = 8443
        private const val DEFAULT_HEALTH_PORT = 8081
        private const val DEFAULT_COMPLEXITY_CAPACITY = 1000
        private const val DEFAULT_COMPLEXITY_REFILL = 60
        private val DEFAULT_REQUEST_BUDGET = 5.seconds

        fun fromEnvironment(env: Map<String, String>): Result<BffConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                BffConfig(
                    httpsPort = reader.port("BFF_HTTPS_PORT", DEFAULT_HTTPS_PORT),
                    healthPort = reader.port("BFF_HEALTH_PORT", DEFAULT_HEALTH_PORT),
                    tls = TLS.files(env),
                    allowedClients = TLS.allowedClients(env),
                    audience = reader.optional("BFF_AUDIENCE") ?: "bff-graphql",
                    issuer = reader.required("OIDC_ISSUER"),
                    jwksUri = reader.uri("OIDC_JWKS_URI"),
                    tokenUrl = reader.uri("BFF_TOKEN_URL"),
                    clientId = reader.optional("BFF_CLIENT_ID") ?: "eiaf-bff-graphql",
                    gatewayUrl = reader.uri("BFF_GATEWAY_URL"),
                    inventoryTarget = reader.required("BFF_INVENTORY_TARGET"),
                    shippingTarget = reader.required("BFF_SHIPPING_TARGET"),
                    requestBudget = reader.duration("BFF_REQUEST_BUDGET") ?: DEFAULT_REQUEST_BUDGET,
                    introspectionEnabled = reader.flag(INTROSPECTION_ENABLED),
                    complexityCapacity = reader.positive(COMPLEXITY_CAPACITY, DEFAULT_COMPLEXITY_CAPACITY),
                    complexityRefillPerSecond = reader.positive(COMPLEXITY_REFILL, DEFAULT_COMPLEXITY_REFILL),
                )
            return if (reader.violations.isEmpty()) ok(config) else err(ValidationError(reader.violations))
        }
    }
}

/** 環境変数を読み、誤りを [violations] に集める(最初の誤りで止めず、まとめて知らせる)。 */
private class EnvReader(
    private val env: Map<String, String>,
) {
    val violations = mutableListOf<FieldViolation>()

    fun optional(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    fun required(name: String): String = optional(name) ?: "".also { violations += FieldViolation(name, "必須です") }

    fun uri(name: String): URI =
        optional(name)?.let { value ->
            try {
                URI(value)
            } catch (_: java.net.URISyntaxException) {
                PLACEHOLDER.also { violations += FieldViolation(name, "URL にしてください") }
            }
        } ?: PLACEHOLDER.also { violations += FieldViolation(name, "必須です") }

    fun port(
        name: String,
        default: Int,
    ): Int =
        optional(name)?.let { value ->
            value.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                ?: default.also { violations += FieldViolation(name, "0〜65535 のポート番号にしてください") }
        } ?: default

    fun positive(
        name: String,
        default: Int,
    ): Int =
        optional(name)?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 } ?: default.also { violations += FieldViolation(name, "正の整数にしてください") }
        } ?: default

    fun duration(name: String): Duration? =
        optional(name)?.let { value ->
            Duration.parseOrNull(value)?.takeIf { it.isPositive() }
                ?: null.also { violations += FieldViolation(name, "正の期間にしてください(例 PT5S・5s)") }
        }

    fun flag(name: String): Boolean =
        when (optional(name)) {
            null, "false" -> false
            "true" -> true
            else -> false.also { violations += FieldViolation(name, "true か false にしてください") }
        }

    private companion object {
        const val MAX_PORT = 65_535
        val PLACEHOLDER = URI("http://invalid")
    }
}
