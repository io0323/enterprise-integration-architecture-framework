package io.eia.fileexchange.app

import io.eia.fileexchange.adapters.file.InboxPrefixes
import io.eia.fileexchange.adapters.partner.PartnerRegistry
import io.eia.platform.security.secret.SecretName
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * file-exchange の設定(環境変数)。パスワードと S3 の資格情報は値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `FILE_EXCHANGE_DB_URL` | なし(必須) | すべて |
 * | `FILE_EXCHANGE_DB_USER` / `FILE_EXCHANGE_DB_PASSWORD`(または `_FILE`) | `file_exchange` / なし | migrate だけ(所有者) |
 * | `FILE_EXCHANGE_APP_DB_USER` / `FILE_EXCHANGE_APP_DB_PASSWORD`(または `_FILE`) | `file_exchange_app` / なし | serve・receive・sweep |
 * | `FILE_EXCHANGE_S3_ENDPOINT` | なし(serve・receive・sweep では必須) | 例 `http://seaweedfs:8333` |
 * | `FILE_EXCHANGE_S3_BUCKET` | `eiaf-file-exchange` | |
 * | `FILE_EXCHANGE_S3_ACCESS_KEY` / `FILE_EXCHANGE_S3_SECRET_KEY`(`_FILE` も可) | なし | 受け取りの identity(読み取り・一覧・移動。ADR-0032 §1) |
 * | `FILE_EXCHANGE_INBOX_PREFIX` | `inbox/sales-daily/` | 受け口(送り手が置く場所) |
 * | `FILE_EXCHANGE_ARCHIVE_PREFIX` / `_QUARANTINE_PREFIX` | `archive/sales-daily/` / `quarantine/sales-daily/` | 合格・不合格の移動先 |
 * | `FILE_EXCHANGE_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `FILE_EXCHANGE_POLL_INTERVAL` | 30s | serve(ファイルの到着を確かめる間隔) |
 * | `FILE_EXCHANGE_SWEEP_INTERVAL` | 5m | serve(受け口の見回りの間隔) |
 * | `FILE_EXCHANGE_PARTNERS_FILE` | なし(なければ配送しない) | 取引先の台帳(JSON。ADR-0032 §8)のパス |
 * | `FILE_EXCHANGE_PGP_SECRET_KEY` / `FILE_EXCHANGE_PGP_PASSPHRASE`(`_FILE` も可) | なし | 自社の PGP の署名の鍵(配送のときに読む) |
 * | `FILE_EXCHANGE_SFTP_OPEN_DURATION` | 30s | serve・receive(取引先の SFTP の Circuit Breaker が Open のままでいる期間。ADR-0032 §8) |
 * | `FILE_EXCHANGE_DELIVER_RETRY_DELAY` | 5s | serve・receive(配送のステップのリトライの最初の待ち時間。2 倍ずつ延ばし、上限は 1 分) |
 *
 * 取引先ごとの SFTP の鍵・ホスト鍵・PGP の公開鍵は、台帳に書いた名前の秘密情報から読む。
 */
internal data class FileExchangeConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val s3Endpoint: URI?,
    val bucket: String,
    val prefixes: InboxPrefixes,
    val healthPort: Int,
    val pollInterval: Duration,
    val sweepInterval: Duration,
    val partnersFile: Path?,
    val sftpOpenDuration: Duration = DEFAULT_SFTP_OPEN_DURATION,
    val deliverRetryDelay: Duration = DEFAULT_DELIVER_RETRY_DELAY,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("FILE_EXCHANGE_DB_PASSWORD")
        val APP_PASSWORD = SecretName("FILE_EXCHANGE_APP_DB_PASSWORD")
        val S3_ACCESS_KEY = SecretName("FILE_EXCHANGE_S3_ACCESS_KEY")
        val S3_SECRET_KEY = SecretName("FILE_EXCHANGE_S3_SECRET_KEY")
        val PGP_SECRET_KEY = SecretName("FILE_EXCHANGE_PGP_SECRET_KEY")
        val PGP_PASSPHRASE = SecretName("FILE_EXCHANGE_PGP_PASSPHRASE")
        const val DB_URL = "FILE_EXCHANGE_DB_URL"
        const val S3_ENDPOINT = "FILE_EXCHANGE_S3_ENDPOINT"
        const val S3_BUCKET = "FILE_EXCHANGE_S3_BUCKET"
        const val INBOX_PREFIX = "FILE_EXCHANGE_INBOX_PREFIX"
        const val ARCHIVE_PREFIX = "FILE_EXCHANGE_ARCHIVE_PREFIX"
        const val QUARANTINE_PREFIX = "FILE_EXCHANGE_QUARANTINE_PREFIX"
        const val HEALTH_PORT = "FILE_EXCHANGE_HEALTH_PORT"
        const val POLL_INTERVAL = "FILE_EXCHANGE_POLL_INTERVAL"
        const val SWEEP_INTERVAL = "FILE_EXCHANGE_SWEEP_INTERVAL"
        const val PARTNERS_FILE = "FILE_EXCHANGE_PARTNERS_FILE"
        const val SFTP_OPEN_DURATION = "FILE_EXCHANGE_SFTP_OPEN_DURATION"
        const val DELIVER_RETRY_DELAY = "FILE_EXCHANGE_DELIVER_RETRY_DELAY"
        private const val MAX_PORT = 65_535
        private const val DEFAULT_HEALTH_PORT = 8081
        private val DEFAULT_POLL_INTERVAL = 30.seconds
        private val DEFAULT_SWEEP_INTERVAL = 5.minutes
        private val DEFAULT_SFTP_OPEN_DURATION = 30.seconds
        private val DEFAULT_DELIVER_RETRY_DELAY = 5.seconds
        private val PREFIX = Regex("^([a-z0-9][a-z0-9-]*/)+$")

        @Suppress("CyclomaticComplexMethod") // 項目ごとの既定と検査を並べる
        fun fromEnvironment(env: Map<String, String>): Result<FileExchangeConfig, ValidationError> {
            val violations = mutableListOf<FieldViolation>()

            fun optional(name: String) = env[name]?.takeIf { it.isNotBlank() }

            fun prefix(
                name: String,
                default: String,
            ): String =
                (optional(name) ?: default).also {
                    if (!PREFIX.matches(it)) violations += FieldViolation(name, "英小文字・数字・- の段を / で区切り、/ で終えてください")
                }

            fun duration(
                name: String,
                default: Duration,
            ): Duration =
                optional(name)?.let { value ->
                    Duration.parseOrNull(value)?.takeIf { it.isPositive() }
                        ?: default.also { violations += FieldViolation(name, "正の期間にしてください(例 30s)") }
                } ?: default

            val endpoint =
                optional(S3_ENDPOINT)?.let { value ->
                    val uri =
                        try {
                            URI(value)
                        } catch (_: URISyntaxException) {
                            null
                        }
                    uri?.takeIf { it.scheme in setOf("http", "https") && it.host != null }
                        ?: null.also { violations += FieldViolation(S3_ENDPOINT, "http(s) の絶対 URL にしてください") }
                }
            val port =
                optional(HEALTH_PORT)?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                        ?: DEFAULT_HEALTH_PORT.also { violations += FieldViolation(HEALTH_PORT, "0〜65535 のポート番号にしてください") }
                } ?: DEFAULT_HEALTH_PORT
            val dbUrl = optional(DB_URL) ?: "".also { violations += FieldViolation(DB_URL, "必須です") }
            val inbox = prefix(INBOX_PREFIX, "inbox/sales-daily/")
            val archive = prefix(ARCHIVE_PREFIX, "archive/sales-daily/")
            val quarantine = prefix(QUARANTINE_PREFIX, "quarantine/sales-daily/")
            if (listOf(inbox, archive, quarantine).distinct().size < listOf(inbox, archive, quarantine).size) {
                violations += FieldViolation(INBOX_PREFIX, "受け口・archive・quarantine は別の接頭辞にしてください")
            }
            val config =
                FileExchangeConfig(
                    dbUrl = dbUrl,
                    ownerUser = optional("FILE_EXCHANGE_DB_USER") ?: "file_exchange",
                    appUser = optional("FILE_EXCHANGE_APP_DB_USER") ?: "file_exchange_app",
                    s3Endpoint = endpoint,
                    bucket = optional(S3_BUCKET) ?: "eiaf-file-exchange",
                    prefixes = if (violations.isEmpty()) InboxPrefixes(inbox, archive, quarantine) else DEFAULT_PREFIXES,
                    healthPort = port,
                    pollInterval = duration(POLL_INTERVAL, DEFAULT_POLL_INTERVAL),
                    sweepInterval = duration(SWEEP_INTERVAL, DEFAULT_SWEEP_INTERVAL),
                    partnersFile = optional(PARTNERS_FILE)?.let { Path.of(it) },
                    sftpOpenDuration = duration(SFTP_OPEN_DURATION, DEFAULT_SFTP_OPEN_DURATION),
                    deliverRetryDelay = duration(DELIVER_RETRY_DELAY, DEFAULT_DELIVER_RETRY_DELAY),
                )
            return if (violations.isEmpty()) ok(config) else err(ValidationError(violations))
        }

        private val DEFAULT_PREFIXES = InboxPrefixes("inbox/sales-daily/", "archive/sales-daily/", "quarantine/sales-daily/")
    }

    /** 取引先の台帳を読む。台帳のファイルの設定がなければ、取引先のない台帳(配送しない)。 */
    fun partners(): Result<PartnerRegistry, ValidationError> {
        val file = partnersFile ?: return ok(PartnerRegistry(emptyList()))
        return try {
            PartnerRegistry.parse(Files.readString(file))
        } catch (_: IOException) {
            err(ValidationError.of(PARTNERS_FILE, "取引先の台帳のファイルを読めません"))
        }
    }

    /** serve・receive・sweep に要る S3 のエンドポイント。 */
    fun forRun(): Result<URI, ValidationError> =
        s3Endpoint?.let { ok(it) } ?: err(ValidationError.of(S3_ENDPOINT, "serve・receive・sweep では必須です"))
}
