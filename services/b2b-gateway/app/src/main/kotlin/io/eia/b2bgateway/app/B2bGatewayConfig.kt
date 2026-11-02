package io.eia.b2bgateway.app

import io.eia.platform.security.secret.SecretName
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * b2b-gateway の設定(環境変数)。パスワード・S3 の資格情報・PGP の鍵・クライアントの秘密は、値ではなく名前([SecretName])だけを持ち、
 * `SecretProvider` から読む(`<名前>_FILE` でファイルからも読める)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `B2B_GATEWAY_DB_URL` | なし(必須) | すべて |
 * | `B2B_GATEWAY_DB_USER` / `B2B_GATEWAY_DB_PASSWORD` | `b2b_gateway` / なし | migrate だけ(所有者) |
 * | `B2B_GATEWAY_APP_DB_USER` / `B2B_GATEWAY_APP_DB_PASSWORD` | `b2b_gateway_app` / なし | serve・codes |
 * | `B2B_GATEWAY_PARTNERS_FILE` | なし(serve・codes では必須) | 取引先の台帳(platform/trading-partner。ADR-0040 §2)のパス |
 * | `B2B_GATEWAY_S3_ENDPOINT` | なし(serve では必須) | 原本の置き場(例 `http://seaweedfs:8333`) |
 * | `B2B_GATEWAY_S3_BUCKET` | `eiaf-b2b-originals` | Object Lock を有効にしたバケット(ADR-0040 §5) |
 * | `B2B_GATEWAY_S3_ACCESS_KEY` / `B2B_GATEWAY_S3_SECRET_KEY` | なし | 原本を書く identity(`eiaf-b2b-gateway`) |
 * | `B2B_GATEWAY_ORIGINAL_RETENTION` | なし(serve では必須) | 原本の保持期間(例 `P3650D`・`1d`。法令の要件は担当部門が決める。ADR-0040 §5) |
 * | `B2B_GATEWAY_PGP_SECRET_KEY` / `B2B_GATEWAY_PGP_PASSPHRASE` | なし | 自社の PGP の鍵(復号と署名。ADR-0040 §8) |
 * | `B2B_GATEWAY_ARCHIVE_PGP_PUBLIC_KEY` | なし | 保管用の鍵の公開鍵(復号した交換の写し。秘密鍵はサービスに渡さない) |
 * | `B2B_GATEWAY_GATEWAY_URL` | なし(serve では必須) | API Gateway(例 `http://apisix:9080`。order の API は `/sales/v1/orders`) |
 * | `B2B_GATEWAY_TOKEN_URL` | なし(serve では必須) | Keycloak のトークンのエンドポイント |
 * | `B2B_GATEWAY_CLIENT_ID` / `B2B_GATEWAY_CLIENT_SECRET` | `eiaf-b2b-gateway` / なし | Client Credentials(`sales.order:write` だけ) |
 * | `B2B_GATEWAY_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `B2B_GATEWAY_POLL_INTERVAL` | 30s | serve(取引先の受け取りのディレクトリを見回る間隔) |
 * | `B2B_GATEWAY_SEND_INTERVAL` | 30s | serve(送信待ちの応答を送る間隔) |
 * | `B2B_GATEWAY_SUBMIT_INTERVAL` | 30s | serve(受け付けた注文を order の API に送る間隔) |
 * | `B2B_GATEWAY_METRICS_INTERVAL` | 1m | serve(送信待ちの期限のメトリクスを DB から読み直す間隔) |
 * | `B2B_GATEWAY_SUBMIT_WINDOW` | 12h | serve(送り直しの上限。order の Idempotency-Key の保存期間 24 時間より短く。ADR-0040 §6) |
 * | `B2B_GATEWAY_SFTP_OPEN_DURATION` | 30s | serve(取引先の SFTP の Circuit Breaker が Open のままでいる期間) |
 * | `B2B_GATEWAY_WORK_DIR` | `java.io.tmpdir` | PGP の一時ファイルの置き場(処理が終わると消す) |
 *
 * 取引先ごとの SFTP の鍵・ホスト鍵・PGP の公開鍵は、台帳に書いた名前の秘密情報から読む。
 */
internal data class B2bGatewayConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val partnersFile: Path?,
    val s3Endpoint: URI?,
    val bucket: String,
    val retention: Duration?,
    val gatewayUrl: URI?,
    val tokenUrl: URI?,
    val clientId: String,
    val healthPort: Int,
    val intervals: Intervals,
    val submitWindow: Duration,
    val sftpOpenDuration: Duration,
    val workDir: Path,
) {
    /** 常駐の処理の間隔。 */
    data class Intervals(
        val poll: Duration,
        val send: Duration,
        val submit: Duration,
        val metrics: Duration,
    )

    /** serve に要る値(起動の前に確かめる)。 */
    data class Serve(
        val s3Endpoint: URI,
        val retention: Duration,
        val gatewayUrl: URI,
        val tokenUrl: URI,
    )

    companion object {
        val OWNER_PASSWORD = SecretName("B2B_GATEWAY_DB_PASSWORD")
        val APP_PASSWORD = SecretName("B2B_GATEWAY_APP_DB_PASSWORD")
        val S3_ACCESS_KEY = SecretName("B2B_GATEWAY_S3_ACCESS_KEY")
        val S3_SECRET_KEY = SecretName("B2B_GATEWAY_S3_SECRET_KEY")
        val PGP_SECRET_KEY = SecretName("B2B_GATEWAY_PGP_SECRET_KEY")
        val PGP_PASSPHRASE = SecretName("B2B_GATEWAY_PGP_PASSPHRASE")
        val ARCHIVE_PGP_PUBLIC_KEY = SecretName("B2B_GATEWAY_ARCHIVE_PGP_PUBLIC_KEY")
        val CLIENT_SECRET = SecretName("B2B_GATEWAY_CLIENT_SECRET")
        const val DB_URL = "B2B_GATEWAY_DB_URL"
        const val PARTNERS_FILE = "B2B_GATEWAY_PARTNERS_FILE"
        const val S3_ENDPOINT = "B2B_GATEWAY_S3_ENDPOINT"
        const val S3_BUCKET = "B2B_GATEWAY_S3_BUCKET"
        const val ORIGINAL_RETENTION = "B2B_GATEWAY_ORIGINAL_RETENTION"
        const val GATEWAY_URL = "B2B_GATEWAY_GATEWAY_URL"
        const val TOKEN_URL = "B2B_GATEWAY_TOKEN_URL"
        const val CLIENT_ID = "B2B_GATEWAY_CLIENT_ID"
        const val HEALTH_PORT = "B2B_GATEWAY_HEALTH_PORT"
        const val POLL_INTERVAL = "B2B_GATEWAY_POLL_INTERVAL"
        const val SEND_INTERVAL = "B2B_GATEWAY_SEND_INTERVAL"
        const val SUBMIT_INTERVAL = "B2B_GATEWAY_SUBMIT_INTERVAL"
        const val METRICS_INTERVAL = "B2B_GATEWAY_METRICS_INTERVAL"
        const val SUBMIT_WINDOW = "B2B_GATEWAY_SUBMIT_WINDOW"
        const val SFTP_OPEN_DURATION = "B2B_GATEWAY_SFTP_OPEN_DURATION"
        const val WORK_DIR = "B2B_GATEWAY_WORK_DIR"
        private const val MAX_PORT = 65_535
        private const val DEFAULT_HEALTH_PORT = 8081
        private val DEFAULT_INTERVAL = 30.seconds
        private val DEFAULT_METRICS_INTERVAL = 1.minutes
        private val DEFAULT_SUBMIT_WINDOW = 12.hours
        private val IDEMPOTENCY_RETENTION = 24.hours

        @Suppress("CyclomaticComplexMethod", "LongMethod") // 項目ごとの既定と検査を並べる
        fun fromEnvironment(env: Map<String, String>): Result<B2bGatewayConfig, ValidationError> {
            val violations = mutableListOf<FieldViolation>()

            fun optional(name: String) = env[name]?.takeIf { it.isNotBlank() }

            fun duration(name: String): Duration? =
                optional(name)?.let { value ->
                    Duration.parseOrNull(value)?.takeIf { it.isPositive() }
                        ?: null.also { violations += FieldViolation(name, "正の期間にしてください(例 30s・12h・P1D)") }
                }

            fun url(name: String): URI? =
                optional(name)?.let { value ->
                    val uri =
                        try {
                            URI(value)
                        } catch (_: URISyntaxException) {
                            null
                        }
                    uri?.takeIf { it.scheme in setOf("http", "https") && it.host != null }
                        ?: null.also { violations += FieldViolation(name, "http(s) の絶対 URL にしてください") }
                }

            val port =
                optional(HEALTH_PORT)?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                        ?: DEFAULT_HEALTH_PORT.also { violations += FieldViolation(HEALTH_PORT, "0〜65535 のポート番号にしてください") }
                } ?: DEFAULT_HEALTH_PORT
            val submitWindow = duration(SUBMIT_WINDOW) ?: DEFAULT_SUBMIT_WINDOW
            if (submitWindow >= IDEMPOTENCY_RETENTION) {
                violations += FieldViolation(SUBMIT_WINDOW, "order の Idempotency-Key の保存期間(24 時間)より短くしてください")
            }
            val config =
                B2bGatewayConfig(
                    dbUrl = optional(DB_URL) ?: "".also { violations += FieldViolation(DB_URL, "必須です") },
                    ownerUser = optional("B2B_GATEWAY_DB_USER") ?: "b2b_gateway",
                    appUser = optional("B2B_GATEWAY_APP_DB_USER") ?: "b2b_gateway_app",
                    partnersFile = optional(PARTNERS_FILE)?.let { Path.of(it) },
                    s3Endpoint = url(S3_ENDPOINT),
                    bucket = optional(S3_BUCKET) ?: "eiaf-b2b-originals",
                    retention = duration(ORIGINAL_RETENTION),
                    gatewayUrl = url(GATEWAY_URL),
                    tokenUrl = url(TOKEN_URL),
                    clientId = optional(CLIENT_ID) ?: "eiaf-b2b-gateway",
                    healthPort = port,
                    intervals =
                        Intervals(
                            poll = duration(POLL_INTERVAL) ?: DEFAULT_INTERVAL,
                            send = duration(SEND_INTERVAL) ?: DEFAULT_INTERVAL,
                            submit = duration(SUBMIT_INTERVAL) ?: DEFAULT_INTERVAL,
                            metrics = duration(METRICS_INTERVAL) ?: DEFAULT_METRICS_INTERVAL,
                        ),
                    submitWindow = submitWindow,
                    sftpOpenDuration = duration(SFTP_OPEN_DURATION) ?: DEFAULT_INTERVAL,
                    workDir = Path.of(optional(WORK_DIR) ?: System.getProperty("java.io.tmpdir")),
                )
            return if (violations.isEmpty()) ok(config) else err(ValidationError(violations))
        }
    }

    /** 取引先の台帳を読む(serve・codes では必須)。 */
    @Suppress("ReturnCount") // 設定がない・読めないのそれぞれで返す
    fun ledger(): Result<TradingPartnerLedger, ValidationError> {
        val file = partnersFile ?: return err(ValidationError.of(PARTNERS_FILE, "serve・codes では必須です"))
        val text =
            try {
                Files.readString(file)
            } catch (_: IOException) {
                return err(ValidationError.of(PARTNERS_FILE, "取引先の台帳のファイルを読めません"))
            }
        return TradingPartnerLedger.parse(text)
    }

    /** serve に要る値。足りなければ、足りない項目をすべて返す。 */
    fun forServe(): Result<Serve, ValidationError> {
        val missing =
            listOfNotNull(
                S3_ENDPOINT.takeIf { s3Endpoint == null },
                ORIGINAL_RETENTION.takeIf { retention == null },
                GATEWAY_URL.takeIf { gatewayUrl == null },
                TOKEN_URL.takeIf { tokenUrl == null },
            )
        if (missing.isNotEmpty()) return err(ValidationError(missing.map { FieldViolation(it, "serve では必須です") }))
        return ok(Serve(checkNotNull(s3Endpoint), checkNotNull(retention), checkNotNull(gatewayUrl), checkNotNull(tokenUrl)))
    }
}
