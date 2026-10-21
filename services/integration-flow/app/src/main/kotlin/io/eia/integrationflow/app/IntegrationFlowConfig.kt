package io.eia.integrationflow.app

import io.eia.integrationflow.adapters.flow.FlowSource
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * integration-flow の設定(環境変数)。パスワードと API トークンは値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 * Kafka と Apicurio はローカルでは未認証(ADR-0008 の縮退)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `INTEGRATION_FLOW_DB_URL` | なし(必須) | migrate・serve |
 * | `INTEGRATION_FLOW_DB_USER` / `INTEGRATION_FLOW_DB_PASSWORD`(または `_FILE`) | `integration_flow` / なし | migrate だけ(所有者) |
 * | `INTEGRATION_FLOW_APP_DB_USER` / `INTEGRATION_FLOW_APP_DB_PASSWORD`(または `_FILE`) | `integration_flow_app` / なし | serve だけ |
 * | `INTEGRATION_FLOW_CDC_DB_USER` | `debezium` | migrate(Outbox の表の SELECT と DB の CONNECT を付ける Debezium のロール) |
 * | `INTEGRATION_FLOW_KAFKA_BOOTSTRAP` | なし(serve では必須) | serve(例 `kafka:9092`) |
 * | `INTEGRATION_FLOW_SCHEMA_REGISTRY_URL` | なし(serve では必須) | serve(例 `http://apicurio:8080/apis/registry/v3`) |
 * | `INTEGRATION_FLOW_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `INTEGRATION_FLOW_FLOWS_DIR` | なし(イメージに含めた contracts/flows) | serve・validate(Flow の定義を読むディレクトリ) |
 * | `INTEGRATION_FLOW_CRM_API_URL` | なし(CRM の poll の trigger があれば serve で必須) | serve(例 `http://saas-mock:8080`) |
 * | `INTEGRATION_FLOW_CRM_API_TOKEN`(または `_FILE`) | なし(読めなければ同期が失敗する) | serve(CRM の API トークン。呼ぶたびに読む) |
 * | `INTEGRATION_FLOW_POLL_INTERVAL` | なし(Flow の `every`) | serve(定期の同期の間隔を上書きする。E2E で短くする) |
 * | `INTEGRATION_FLOW_PURGE_INTERVAL` | 1h | serve(保持期間を過ぎた冪等消費の記録の削除の間隔) |
 */
internal data class IntegrationFlowConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val cdcUser: String,
    val kafkaBootstrap: String?,
    val schemaRegistryUrl: String?,
    val healthPort: Int,
    val flows: FlowSource,
    val crmApiUrl: URI?,
    val pollInterval: Duration?,
    val purgeInterval: Duration,
) {
    companion object {
        val OWNER_PASSWORD = SecretName("INTEGRATION_FLOW_DB_PASSWORD")
        val APP_PASSWORD = SecretName("INTEGRATION_FLOW_APP_DB_PASSWORD")
        val CRM_API_TOKEN = SecretName("INTEGRATION_FLOW_CRM_API_TOKEN")
        const val KAFKA_BOOTSTRAP = "INTEGRATION_FLOW_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "INTEGRATION_FLOW_SCHEMA_REGISTRY_URL"
        const val FLOWS_DIR = "INTEGRATION_FLOW_FLOWS_DIR"
        const val CRM_API_URL = "INTEGRATION_FLOW_CRM_API_URL"
        private const val DEFAULT_HEALTH_PORT = 8081
        private const val MAX_PORT = 65_535
        private val DEFAULT_PURGE_INTERVAL = 1.hours

        /** Flow の定義の置き場(ディレクトリの指定がなければイメージに含めたもの)。validate も使う。 */
        fun flows(env: Map<String, String>): FlowSource =
            env[FLOWS_DIR]?.takeIf { it.isNotBlank() }?.let { FlowSource.Directory(Path.of(it)) } ?: FlowSource.Classpath

        @Suppress("CyclomaticComplexMethod") // 項目ごとの既定と検査を並べる
        fun fromEnvironment(env: Map<String, String>): Result<IntegrationFlowConfig, ValidationError> {
            val violations = mutableListOf<FieldViolation>()

            fun optional(name: String) = env[name]?.takeIf { it.isNotBlank() }

            fun duration(name: String): Duration? =
                optional(name)?.let { value ->
                    Duration.parseOrNull(value)?.takeIf { it.isPositive() }
                        ?: null.also { violations += FieldViolation(name, "正の期間にしてください(例 15m)") }
                }
            val port =
                optional("INTEGRATION_FLOW_HEALTH_PORT")?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                        ?: DEFAULT_HEALTH_PORT.also {
                            violations +=
                                FieldViolation(
                                    "INTEGRATION_FLOW_HEALTH_PORT",
                                    "0〜65535 のポート番号にしてください",
                                )
                        }
                } ?: DEFAULT_HEALTH_PORT
            val crm =
                optional(CRM_API_URL)?.let { value ->
                    val uri =
                        try {
                            URI(value)
                        } catch (_: URISyntaxException) {
                            null
                        }
                    uri?.takeIf { it.scheme in setOf("http", "https") && it.host != null }
                        ?: null.also { violations += FieldViolation(CRM_API_URL, "http(s) の絶対 URL にしてください") }
                }
            val config =
                IntegrationFlowConfig(
                    dbUrl =
                        optional("INTEGRATION_FLOW_DB_URL") ?: "".also { violations += FieldViolation("INTEGRATION_FLOW_DB_URL", "必須です") },
                    ownerUser = optional("INTEGRATION_FLOW_DB_USER") ?: "integration_flow",
                    appUser = optional("INTEGRATION_FLOW_APP_DB_USER") ?: "integration_flow_app",
                    cdcUser = optional("INTEGRATION_FLOW_CDC_DB_USER") ?: "debezium",
                    kafkaBootstrap = optional(KAFKA_BOOTSTRAP),
                    schemaRegistryUrl = optional(SCHEMA_REGISTRY_URL),
                    healthPort = port,
                    flows = flows(env),
                    crmApiUrl = crm,
                    pollInterval = duration("INTEGRATION_FLOW_POLL_INTERVAL"),
                    purgeInterval = duration("INTEGRATION_FLOW_PURGE_INTERVAL") ?: DEFAULT_PURGE_INTERVAL,
                )
            return if (violations.isEmpty()) ok(config) else err(ValidationError(violations))
        }
    }

    /** serve に必要な設定(Kafka と Apicurio)がそろっているか。 */
    fun forServe(): Result<ServeSettings, ValidationError> {
        val violations =
            buildList {
                if (kafkaBootstrap == null) add(FieldViolation(KAFKA_BOOTSTRAP, "serve では必須です"))
                if (schemaRegistryUrl == null) add(FieldViolation(SCHEMA_REGISTRY_URL, "serve では必須です"))
            }
        return if (violations.isEmpty()) {
            ok(
                ServeSettings(checkNotNull(kafkaBootstrap), checkNotNull(schemaRegistryUrl)),
            )
        } else {
            err(ValidationError(violations))
        }
    }
}

internal data class ServeSettings(
    val kafkaBootstrap: String,
    val schemaRegistryUrl: String,
)

/** 起動の前に確かめた値(設定・serve の接続先・アプリのロールのパスワード・読み込んだ Flow)。 */
internal data class ServeInputs(
    val config: IntegrationFlowConfig,
    val serve: ServeSettings,
    val appPassword: Secret,
    val catalog: FlowCatalog,
)
