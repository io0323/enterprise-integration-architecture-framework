package io.eia.integrationflow.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.integrationflow.adapters.flow.YamlFlowDefinitions
import io.eia.integrationflow.adapters.out.persistence.IntegrationFlowSchema
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import org.slf4j.LoggerFactory

/**
 * サブコマンドの振り分けと終了コード(0: 成功 / 1: 実行の失敗 / 2: 使い方・設定・Flow の定義の誤り)。order-service と同じ形(ADR-0024 §2)。
 *
 * - `migrate`: 所有者の資格情報でマイグレーションして終わる。
 * - `serve`: アプリのロールの資格情報だけで、取り込みのトピックの購読と定期の同期を始め、終了の合図(SIGTERM)まで動く。
 *   起動時に Flow の定義を検証し、誤りがあれば起動しない(ADR-0036 §1)。
 * - `validate`: Flow の定義を読み込み、起動時と同じ検証をして終わる(Flow の YAML を足したときに、起動の前に確かめる)。
 */
internal object IntegrationFlowCommands {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val MIGRATE_POOL_SIZE = 3

    /** serve の環境にあってはならない変数(所有者のパスワード。ADR-0024 §2)。 */
    val FORBIDDEN_FOR_SERVE: List<String> =
        listOf(IntegrationFlowConfig.OWNER_PASSWORD.value, IntegrationFlowConfig.OWNER_PASSWORD.value + SecretName.FILE_SUFFIX)

    private val logger = LoggerFactory.getLogger(IntegrationFlowCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int =
        when (args.firstOrNull()) {
            "migrate" -> {
                migrate(env)
            }

            "serve" -> {
                serve(env)
            }

            "validate" -> {
                validate(env)
            }

            else -> {
                logger.error("使い方: integration-flow migrate | serve | validate")
                USAGE
            }
        }

    private fun serve(env: Map<String, String>): Int {
        FORBIDDEN_FOR_SERVE.firstOrNull { env.containsKey(it) }?.let {
            logger.error("serve の環境に所有者のパスワード({})を渡さないでください", it)
            return USAGE
        }
        return when (val server = IntegrationFlowServer.start(env)) {
            is Result.Ok -> {
                server.value.awaitTermination()
                OK
            }

            is Result.Err -> {
                server.error.violations.forEach { logger.error("起動できません: {}: {}", it.field, it.reason) }
                USAGE
            }
        }
    }

    private fun validate(env: Map<String, String>): Int =
        when (val loaded = LoadFlowsService(YamlFlowDefinitions(IntegrationFlowConfig.flows(env)))()) {
            is Result.Ok -> {
                loaded.value.flows.forEach { logger.info("Flow {}.v{} を読み込みました(canonical={})", it.id, it.version, it.canonical) }
                OK
            }

            is Result.Err -> {
                loaded.error.violations.forEach { logger.error("Flow の定義の誤り: {}: {}", it.field, it.reason) }
                USAGE
            }
        }

    /** 所有者の資格情報でマイグレーションする。パスワードはこのコマンドにだけ渡す。 */
    private fun migrate(env: Map<String, String>): Int =
        when (val inputs = migrateInputs(env)) {
            is Result.Err -> {
                USAGE.also { logger.error("migrate の設定が不正です: {}", inputs.error.message) }
            }

            is Result.Ok -> {
                val (config, password) = inputs.value
                ownerDataSource(config, password).use {
                    when (val migrated = IntegrationFlowSchema.migrate(it, config.appUser, config.cdcUser)) {
                        is Result.Ok -> OK.also { logger.info("マイグレーションが終わりました") }
                        is Result.Err -> FAILED.also { logger.error("マイグレーションに失敗しました: {}", migrated.error.message) }
                    }
                }
            }
        }

    private fun migrateInputs(env: Map<String, String>): Result<Pair<IntegrationFlowConfig, Secret>, ValidationError> =
        IntegrationFlowConfig.fromEnvironment(env).flatMap { config ->
            when (val secret = EnvSecretProvider(env).get(IntegrationFlowConfig.OWNER_PASSWORD)) {
                is Result.Ok -> ok(config to secret.value)
                is Result.Err -> err(ValidationError.of(IntegrationFlowConfig.OWNER_PASSWORD.value, secret.error.message))
            }
        }

    private fun ownerDataSource(
        config: IntegrationFlowConfig,
        password: Secret,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.ownerUser
                this.password = password.reveal()
                // Flyway は履歴の管理とマイグレーションの実行に、接続を 2 本以上使う(1 本だと接続を待って失敗する)
                maximumPoolSize = MIGRATE_POOL_SIZE
                poolName = "integration-flow-migrate"
            },
        )
}
