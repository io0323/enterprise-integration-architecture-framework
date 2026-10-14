package io.eia.batchetl.app

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.datetime.LocalTime
import kotlin.time.Duration
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
) {
    companion object {
        val OWNER_PASSWORD = SecretName("BATCH_ETL_DB_PASSWORD")
        val APP_PASSWORD = SecretName("BATCH_ETL_APP_DB_PASSWORD")
        const val DB_URL = "BATCH_ETL_DB_URL"
        const val KAFKA_BOOTSTRAP = "BATCH_ETL_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "BATCH_ETL_SCHEMA_REGISTRY_URL"
        const val HEALTH_PORT = "BATCH_ETL_HEALTH_PORT"
        const val DAILY_AT = "BATCH_ETL_DAILY_AT"
        const val SLA_AT = "BATCH_ETL_SLA_AT"
        const val POLL_INTERVAL = "BATCH_ETL_POLL_INTERVAL"
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

    private companion object {
        const val MAX_PORT = 65_535
        val TIME = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$")
    }
}
