package io.eia.batchetl.app

import io.eia.batchetl.application.port.outbound.SalesFileFormat
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * batch-etl の設定(環境変数)。パスワードは値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 * Kafka と Apicurio はローカルでは未認証(ADR-0008 の縮退・#26・#29)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `BATCH_ETL_DB_URL` | なし(必須) | すべて |
 * | `BATCH_ETL_DB_USER` / `BATCH_ETL_DB_PASSWORD`(または `_FILE`) | `batch_etl` / なし | migrate だけ(所有者) |
 * | `BATCH_ETL_APP_DB_USER` / `BATCH_ETL_APP_DB_PASSWORD`(または `_FILE`) | `batch_etl_app` / なし | serve・run(アプリのロール) |
 * | `BATCH_ETL_KAFKA_BOOTSTRAP` | なし(serve・run では必須) | serve・run(例 `kafka:9092`) |
 * | `BATCH_ETL_SCHEMA_REGISTRY_URL` | なし(serve・run では必須) | serve・run(例 `http://apicurio:8080/apis/registry/v3`) |
 * | `BATCH_ETL_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `BATCH_ETL_DAILY_AT` | `01:00` | serve(日次の DAG を起動する日本時間の時刻。前日の分を処理する) |
 * | `BATCH_ETL_SLA_AT` | `06:00` | serve・run(前日の分を終える期限。日本時間の時刻) |
 * | `BATCH_ETL_POLL_INTERVAL` | 30s | serve(起動の条件と SLA の予測を確かめる間隔) |
 * | `BATCH_ETL_LAG_PROBE_INTERVAL` | 5m | serve(取り込みの遅れ・保持期間・消えたイベントを確かめる間隔。ADR-0031 §7) |
 * | `BATCH_ETL_GATEWAY_URL` | なし(なければ週次の照合をしない) | serve・reconcile(API Gateway。例 `http://apisix:9080`) |
 * | `BATCH_ETL_TOKEN_URL` | なし(`BATCH_ETL_GATEWAY_URL` があれば必須) | serve・reconcile(Keycloak のトークンエンドポイント) |
 * | `BATCH_ETL_CLIENT_ID` / `BATCH_ETL_CLIENT_SECRET`(`_FILE` も可) | `eiaf-batch-etl` / なし | serve・reconcile(スコープ `sales.order:report` だけ) |
 * | `BATCH_ETL_RECONCILE_ON` / `BATCH_ETL_RECONCILE_AT` | `MONDAY` / `07:00` | serve(週次の照合の曜日と日本時間の時刻。日次の SLA の期限の後) |
 * | `BATCH_ETL_FILE_S3_ENDPOINT` | なし(なければ日次の売上ファイルを書かない) | serve・sales-file(S3 互換ストレージ。例 `http://seaweedfs:8333`。ADR-0031 §8) |
 * | `BATCH_ETL_FILE_S3_BUCKET` / `BATCH_ETL_FILE_PREFIX` | `eiaf-file-exchange` / `inbox/sales-daily/` | serve・sales-file(置き場。受け口の自分の接頭辞) |
 * | `BATCH_ETL_FILE_S3_ACCESS_KEY` / `BATCH_ETL_FILE_S3_SECRET_KEY`(`_FILE` も可) | なし | serve・sales-file(受け口に書くだけの identity。ADR-0032 §1) |
 * | `BATCH_ETL_FILE_FORMATS` | `parquet` | serve・sales-file(`parquet`・`csv` をカンマ区切り。順に書く) |
 * | `BATCH_ETL_FILE_SLA_AT` | `07:00` | serve・sales-file(前日の分の売上ファイルを置く期限。日本時間の時刻。日次の SLA の後) |
 */
internal data class BatchEtlConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val bootstrapServers: String?,
    val schemaRegistryUrl: String?,
    val healthPort: Int,
    val dailyAt: LocalTime,
    val slaAt: LocalTime,
    val pollInterval: Duration,
    val reconcile: ReconcileSettings? = null,
    val lagProbeInterval: Duration = DEFAULT_LAG_PROBE_INTERVAL,
    val salesFile: SalesFileSettings? = null,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("BATCH_ETL_DB_PASSWORD")
        val APP_PASSWORD = SecretName("BATCH_ETL_APP_DB_PASSWORD")
        val CLIENT_SECRET = SecretName("BATCH_ETL_CLIENT_SECRET")
        val FILE_S3_ACCESS_KEY = SecretName("BATCH_ETL_FILE_S3_ACCESS_KEY")
        val FILE_S3_SECRET_KEY = SecretName("BATCH_ETL_FILE_S3_SECRET_KEY")
        const val FILE_S3_ENDPOINT = "BATCH_ETL_FILE_S3_ENDPOINT"
        const val FILE_S3_BUCKET = "BATCH_ETL_FILE_S3_BUCKET"
        const val FILE_PREFIX = "BATCH_ETL_FILE_PREFIX"
        const val FILE_FORMATS = "BATCH_ETL_FILE_FORMATS"
        const val FILE_SLA_AT = "BATCH_ETL_FILE_SLA_AT"
        const val GATEWAY_URL = "BATCH_ETL_GATEWAY_URL"
        const val TOKEN_URL = "BATCH_ETL_TOKEN_URL"
        const val CLIENT_ID = "BATCH_ETL_CLIENT_ID"
        const val RECONCILE_ON = "BATCH_ETL_RECONCILE_ON"
        const val RECONCILE_AT = "BATCH_ETL_RECONCILE_AT"
        const val DB_URL = "BATCH_ETL_DB_URL"
        const val KAFKA_BOOTSTRAP = "BATCH_ETL_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "BATCH_ETL_SCHEMA_REGISTRY_URL"
        const val HEALTH_PORT = "BATCH_ETL_HEALTH_PORT"
        const val DAILY_AT = "BATCH_ETL_DAILY_AT"
        const val SLA_AT = "BATCH_ETL_SLA_AT"
        const val POLL_INTERVAL = "BATCH_ETL_POLL_INTERVAL"
        const val LAG_PROBE_INTERVAL = "BATCH_ETL_LAG_PROBE_INTERVAL"
        private val DEFAULT_LAG_PROBE_INTERVAL = 5.minutes
        private const val DEFAULT_HEALTH_PORT = 8081
        private val DEFAULT_DAILY_AT = LocalTime(1, 0)
        private val DEFAULT_SLA_AT = LocalTime(6, 0)
        private val DEFAULT_POLL_INTERVAL = 30.seconds

        fun fromEnvironment(env: Map<String, String>): Result<BatchEtlConfig, ValidationError> {
            val reader = EnvReader(env)
            val config =
                BatchEtlConfig(
                    dbUrl = reader.required(DB_URL),
                    ownerUser = reader.optional("BATCH_ETL_DB_USER") ?: "batch_etl",
                    appUser = reader.optional("BATCH_ETL_APP_DB_USER") ?: "batch_etl_app",
                    bootstrapServers = reader.optional(KAFKA_BOOTSTRAP),
                    schemaRegistryUrl = reader.optional(SCHEMA_REGISTRY_URL),
                    healthPort = reader.port(HEALTH_PORT, DEFAULT_HEALTH_PORT),
                    dailyAt = reader.time(DAILY_AT, DEFAULT_DAILY_AT),
                    slaAt = reader.time(SLA_AT, DEFAULT_SLA_AT),
                    pollInterval = reader.duration(POLL_INTERVAL) ?: DEFAULT_POLL_INTERVAL,
                    reconcile = reader.reconcile(),
                    lagProbeInterval = reader.duration(LAG_PROBE_INTERVAL) ?: DEFAULT_LAG_PROBE_INTERVAL,
                    salesFile = reader.salesFile(),
                )
            return if (reader.violations.isEmpty()) ok(config) else err(ValidationError(reader.violations))
        }
    }

    /** serve・run に必要な設定(Kafka と Apicurio)がそろっているか。 */
    fun forRun(): Result<RunSettings, ValidationError> {
        val violations =
            buildList {
                if (bootstrapServers == null) add(FieldViolation(KAFKA_BOOTSTRAP, "serve・run では必須です"))
                if (schemaRegistryUrl == null) add(FieldViolation(SCHEMA_REGISTRY_URL, "serve・run では必須です"))
            }
        return if (violations.isEmpty()) {
            ok(
                RunSettings(checkNotNull(bootstrapServers), checkNotNull(schemaRegistryUrl)),
            )
        } else {
            err(ValidationError(violations))
        }
    }
}

/** 週次の照合(ADR-0031 §6)の設定。 */
internal data class ReconcileSettings(
    val gatewayUrl: URI,
    val tokenUrl: URI,
    val clientId: String,
    val weeklyOn: DayOfWeek,
    val at: LocalTime,
)

/**
 * 日次の売上ファイル(INT-SALES-004。ADR-0031 §8)の設定。
 *
 * @property prefix 置き場のキーの接頭辞(`/` で終わる)
 * @property formats 書く形式(順に書く。重複なし)
 */
internal data class SalesFileSettings(
    val endpoint: URI,
    val bucket: String,
    val prefix: String,
    val formats: List<SalesFileFormat>,
    val slaAt: LocalTime,
)

internal data class RunSettings(
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
                ?: null.also { violations += FieldViolation(name, "正の期間にしてください(例 30s)") }
        }

    fun time(
        name: String,
        default: LocalTime,
    ): LocalTime =
        optional(name)?.let { value ->
            TIME.matchEntire(value)?.let { LocalTime(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                ?: default.also { violations += FieldViolation(name, "HH:mm(00:00〜23:59)にしてください") }
        } ?: default

    /** 照合の設定。`BATCH_ETL_GATEWAY_URL` がなければ null(照合をしない)。 */
    fun reconcile(): ReconcileSettings? {
        val gateway = url(BatchEtlConfig.GATEWAY_URL) ?: return null
        val token = url(BatchEtlConfig.TOKEN_URL)
        if (token == null && optional(BatchEtlConfig.TOKEN_URL) == null) {
            violations += FieldViolation(BatchEtlConfig.TOKEN_URL, "${BatchEtlConfig.GATEWAY_URL} があれば必須です")
        }
        val day =
            optional(BatchEtlConfig.RECONCILE_ON)?.let { value ->
                DayOfWeek.entries.firstOrNull { it.name == value }
                    ?: DayOfWeek.MONDAY.also { violations += FieldViolation(BatchEtlConfig.RECONCILE_ON, "曜日(MONDAY〜SUNDAY)にしてください") }
            } ?: DayOfWeek.MONDAY
        val at = time(BatchEtlConfig.RECONCILE_AT, DEFAULT_RECONCILE_AT)
        return token?.let { ReconcileSettings(gateway, it, optional(BatchEtlConfig.CLIENT_ID) ?: "eiaf-batch-etl", day, at) }
    }

    /** 日次の売上ファイルの設定。`BATCH_ETL_FILE_S3_ENDPOINT` がなければ null(売上ファイルを書かない)。 */
    fun salesFile(): SalesFileSettings? {
        val endpoint = url(BatchEtlConfig.FILE_S3_ENDPOINT) ?: return null
        val bucket = optional(BatchEtlConfig.FILE_S3_BUCKET) ?: "eiaf-file-exchange"
        val prefix = optional(BatchEtlConfig.FILE_PREFIX) ?: "inbox/sales-daily/"
        if (!PREFIX.matches(prefix)) violations += FieldViolation(BatchEtlConfig.FILE_PREFIX, "英小文字・数字・- の段を / で区切り、/ で終えてください")
        val names = (optional(BatchEtlConfig.FILE_FORMATS) ?: "parquet").split(',').map { it.trim() }
        val formats = names.mapNotNull { FORMATS[it] }
        if (formats.size != names.size || formats.distinct().size != formats.size) {
            violations += FieldViolation(BatchEtlConfig.FILE_FORMATS, "parquet・csv をカンマ区切りで、重複なしにしてください")
        }
        return SalesFileSettings(endpoint, bucket, prefix, formats.distinct(), time(BatchEtlConfig.FILE_SLA_AT, DEFAULT_FILE_SLA_AT))
    }

    private fun url(name: String): URI? =
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

    private companion object {
        const val MAX_PORT = 65_535

        /** 週次の照合の既定の時刻(日本時間)。日次の SLA の期限(06:00)の後。 */
        val DEFAULT_RECONCILE_AT = LocalTime(7, 0)
        val TIME = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$")

        /** 売上ファイルの既定の期限(日本時間)。日次の SLA の期限(06:00)の後。 */
        val DEFAULT_FILE_SLA_AT = LocalTime(7, 0)
        val PREFIX = Regex("^([a-z0-9][a-z0-9-]*/)+$")
        val FORMATS = mapOf("parquet" to SalesFileFormat.PARQUET, "csv" to SalesFileFormat.CSV_GZIP)
    }
}
