package io.eia.saasmock.app

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * saas-mock の設定(環境変数)。トークンと署名の鍵は値ではなく名前([SecretName])だけを持ち、`SecretProvider` から使うたびに読む
 * (再起動なしで鍵を差し替えるため。ADR-0035 §2)。
 *
 * | 環境変数 | 既定 | 用途 |
 * |---|---|---|
 * | `SAAS_MOCK_PORT` | 8080 | CRM の API・管理の API・`/health/live`・`/health/ready` |
 * | `SAAS_MOCK_API_TOKEN`(または `_FILE`) | なし(必須) | CRM の API の Bearer トークン(連携の側が使う) |
 * | `SAAS_MOCK_ADMIN_TOKEN`(または `_FILE`) | なし(必須) | 管理の API の Bearer トークン(SaaS の画面の代わり。E2E が使う) |
 * | `SAAS_MOCK_WEBHOOK_URL` | なし(なければ Webhook を送らない) | 受け口の URL(例 `http://apisix:9080/sales/v1/webhooks/crm`) |
 * | `SAAS_MOCK_WEBHOOK_SECRET`(または `_FILE`) | なし(URL があれば必須) | 署名の鍵 |
 * | `SAAS_MOCK_WEBHOOK_SECRET_NEXT`(または `_FILE`) | なし | 鍵の差し替えの間の次の鍵(あれば新旧の両方で署名する) |
 * | `SAAS_MOCK_WEBHOOK_RETRY` | `5s,30s,2m,10m,1h,3h` | 再送の間隔(カンマ区切り。尽くしたら FAILED) |
 * | `SAAS_MOCK_DISPATCH_INTERVAL` | 1s | 送る時刻の来たイベントを確かめる間隔 |
 * | `SAAS_MOCK_RATE_LIMIT` | `20/1s` | CRM の API の Rate Limit(`<回数>/<期間>`) |
 * | `SAAS_MOCK_SEED_CUSTOMERS` | 0 | 起動のときに作る顧客の数(Webhook は送らない。定期の同期の確認用) |
 */
internal data class SaasMockConfig(
    val port: Int,
    val webhookUrl: URI?,
    val retrySchedule: List<Duration>,
    val dispatchInterval: Duration,
    val rateLimit: RateLimiter.Limit,
    val seedCustomers: Int,
) {
    companion object {
        val API_TOKEN = SecretName("SAAS_MOCK_API_TOKEN")
        val ADMIN_TOKEN = SecretName("SAAS_MOCK_ADMIN_TOKEN")
        val WEBHOOK_SECRET = SecretName("SAAS_MOCK_WEBHOOK_SECRET")
        val WEBHOOK_SECRET_NEXT = SecretName("SAAS_MOCK_WEBHOOK_SECRET_NEXT")
        const val PORT = "SAAS_MOCK_PORT"
        const val WEBHOOK_URL = "SAAS_MOCK_WEBHOOK_URL"
        const val WEBHOOK_RETRY = "SAAS_MOCK_WEBHOOK_RETRY"
        const val DISPATCH_INTERVAL = "SAAS_MOCK_DISPATCH_INTERVAL"
        const val RATE_LIMIT = "SAAS_MOCK_RATE_LIMIT"
        const val SEED_CUSTOMERS = "SAAS_MOCK_SEED_CUSTOMERS"
        private const val MAX_PORT = 65_535
        private const val DEFAULT_PORT = 8080
        private const val MAX_SEED = 10_000
        private val DEFAULT_RETRY = listOf(5.seconds, 30.seconds, 2.minutes, 10.minutes, 1.hours, 3.hours)
        private val DEFAULT_DISPATCH_INTERVAL = 1.seconds

        @Suppress("CyclomaticComplexMethod") // 項目ごとの既定と検査を並べる
        fun fromEnvironment(env: Map<String, String>): Result<SaasMockConfig, ValidationError> {
            val violations = mutableListOf<FieldViolation>()

            fun optional(name: String) = env[name]?.takeIf { it.isNotBlank() }

            val port =
                optional(PORT)?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                        ?: DEFAULT_PORT.also { violations += FieldViolation(PORT, "0〜65535 のポート番号にしてください") }
                } ?: DEFAULT_PORT
            val url =
                optional(WEBHOOK_URL)?.let { value ->
                    val uri =
                        try {
                            URI(value)
                        } catch (_: URISyntaxException) {
                            null
                        }
                    uri?.takeIf { it.scheme in setOf("http", "https") && it.host != null }
                        ?: null.also { violations += FieldViolation(WEBHOOK_URL, "http(s) の絶対 URL にしてください") }
                }
            val retry =
                optional(WEBHOOK_RETRY)?.let { value ->
                    value
                        .split(',')
                        .map { Duration.parseOrNull(it.trim())?.takeIf { d -> d.isPositive() } }
                        .takeIf { it.all { d -> d != null } }
                        ?.filterNotNull()
                        ?: DEFAULT_RETRY.also { violations += FieldViolation(WEBHOOK_RETRY, "正の期間をカンマで区切ってください(例 5s,30s)") }
                } ?: DEFAULT_RETRY
            val interval =
                optional(DISPATCH_INTERVAL)?.let { value ->
                    Duration.parseOrNull(value)?.takeIf { it.isPositive() }
                        ?: DEFAULT_DISPATCH_INTERVAL.also { violations += FieldViolation(DISPATCH_INTERVAL, "正の期間にしてください(例 1s)") }
                } ?: DEFAULT_DISPATCH_INTERVAL
            val limit =
                optional(RATE_LIMIT)?.let { value ->
                    RateLimiter.parse(value)
                        ?: RateLimiter.DEFAULT.also { violations += FieldViolation(RATE_LIMIT, "<回数>/<期間> にしてください(例 20/1s)") }
                } ?: RateLimiter.DEFAULT
            val seed =
                optional(SEED_CUSTOMERS)?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 0..MAX_SEED }
                        ?: 0.also { violations += FieldViolation(SEED_CUSTOMERS, "0〜$MAX_SEED の数にしてください") }
                } ?: 0
            val config = SaasMockConfig(port, url, retry, interval, limit, seed)
            return if (violations.isEmpty()) ok(config) else err(ValidationError(violations))
        }
    }
}
