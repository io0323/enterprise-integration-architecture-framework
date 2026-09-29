package io.eia.tools.auditverify

import io.eia.platform.audit.anchor.S3AnchorStoreConfig
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.format.DateTimeParseException

/**
 * 検査の設定。環境変数から読む(`scripts/audit-verify.sh` が infra/local/.env とサービス名から組み立てる)。
 * DB のパスワードと S3 の資格情報は設定に持たず、実行時に SecretProvider から取る。
 * S3 の資格情報(`AUDIT_S3_ACCESS_KEY` / `AUDIT_S3_SECRET_KEY`)には、読み取り専用の `eiaf-audit-verify` の値を渡す(ADR-0017 §7)。
 */
internal data class AuditVerifyConfig(
    val service: ServiceName,
    val jdbcUrl: String,
    val dbUser: String,
    val s3: S3AnchorStoreConfig,
    val minRetention: Duration,
) {
    companion object {
        const val SERVICE = "AUDIT_SERVICE"
        const val JDBC_URL = "AUDIT_JDBC_URL"
        const val DB_USER = "AUDIT_DB_USER"
        val DB_PASSWORD = SecretName("AUDIT_DB_PASSWORD")
        const val S3_ENDPOINT = "AUDIT_S3_ENDPOINT"
        const val S3_BUCKET = "AUDIT_S3_BUCKET"
        const val S3_PATH_STYLE = "AUDIT_S3_PATH_STYLE"
        const val MIN_RETENTION = "AUDIT_MIN_RETENTION"

        const val DEFAULT_S3_ENDPOINT = "http://localhost:19333"
        const val DEFAULT_BUCKET = "eiaf-audit"

        /** ローカル基盤のアンカーの保持期間の既定値と同じ(ADR-0017)。 */
        val DEFAULT_MIN_RETENTION: Duration = Duration.ofDays(1)

        @Suppress("ReturnCount") // 設定の項目ごとに、最初の誤りで返す
        fun from(env: Map<String, String>): Result<AuditVerifyConfig, String> {
            fun value(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

            val service =
                ServiceName.parse(value(SERVICE) ?: return err("$SERVICE がありません(例: order)")).getOrNull()
                    ?: return err("$SERVICE が不正です(英小文字で始まる英小文字・数字・-)")
            val jdbcUrl = value(JDBC_URL) ?: return err("$JDBC_URL がありません")
            if (!jdbcUrl.startsWith("jdbc:postgresql://")) return err("$JDBC_URL は jdbc:postgresql:// で始めてください")
            val dbUser = value(DB_USER) ?: return err("$DB_USER がありません")
            val endpoint = parseEndpoint(value(S3_ENDPOINT)).getOrElse { return err(it) }
            val pathStyle = parsePathStyle(value(S3_PATH_STYLE)).getOrElse { return err(it) }
            val minRetention = parseMinRetention(value(MIN_RETENTION)).getOrElse { return err(it) }
            return ok(
                AuditVerifyConfig(
                    service = service,
                    jdbcUrl = jdbcUrl,
                    dbUser = dbUser,
                    s3 = S3AnchorStoreConfig(endpoint = endpoint, bucket = value(S3_BUCKET) ?: DEFAULT_BUCKET, pathStyle = pathStyle),
                    minRetention = minRetention,
                ),
            )
        }

        private fun parseEndpoint(value: String?): Result<URI, String> {
            val uri =
                try {
                    URI(value ?: DEFAULT_S3_ENDPOINT)
                } catch (e: URISyntaxException) {
                    return err("$S3_ENDPOINT が URI として不正です(${e::class.simpleName})")
                }
            return if (uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
                ok(uri)
            } else {
                err("$S3_ENDPOINT は http(s)://<host>:<port> の形にしてください")
            }
        }

        private fun parsePathStyle(value: String?): Result<Boolean, String> =
            when (value?.lowercase()) {
                null, "true" -> ok(true)
                "false" -> ok(false)
                else -> err("$S3_PATH_STYLE は true か false にしてください")
            }

        private fun parseMinRetention(value: String?): Result<Duration, String> {
            val duration =
                try {
                    value?.let(Duration::parse) ?: DEFAULT_MIN_RETENTION
                } catch (e: DateTimeParseException) {
                    return err("$MIN_RETENTION は ISO 8601 の期間にしてください(例: P1D)(${e::class.simpleName})")
                }
            return if (duration.isNegative) err("$MIN_RETENTION は 0 以上にしてください") else ok(duration)
        }
    }
}
