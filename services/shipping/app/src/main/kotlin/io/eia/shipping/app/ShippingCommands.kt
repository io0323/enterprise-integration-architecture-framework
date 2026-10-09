package io.eia.shipping.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import io.eia.shipping.adapters.out.persistence.ShippingSchema
import org.slf4j.LoggerFactory

/** サブコマンドの振り分けと終了コード(0: 成功 / 1: 実行の失敗 / 2: 使い方・設定の誤り)。 */
internal object ShippingCommands {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val MIGRATE_POOL_SIZE = 3

    /** serve の環境にあってはならない変数(所有者のパスワード。ADR-0024 §2)。 */
    val FORBIDDEN_FOR_SERVE: List<String> =
        listOf(ShippingConfig.OWNER_PASSWORD.value, ShippingConfig.OWNER_PASSWORD.value + SecretName.FILE_SUFFIX)

    private val logger = LoggerFactory.getLogger(ShippingCommands::class.java)

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

            else -> {
                logger.error("使い方: shipping-service migrate | serve")
                USAGE
            }
        }

    private fun serve(env: Map<String, String>): Int {
        FORBIDDEN_FOR_SERVE.firstOrNull { env.containsKey(it) }?.let {
            logger.error("serve の環境に所有者のパスワード({})を渡さないでください", it)
            return USAGE
        }
        return when (val server = ShippingServer.start(env)) {
            is Result.Ok -> {
                server.value.awaitTermination()
                OK
            }

            is Result.Err -> {
                logger.error("起動できません: {}", server.error.message)
                USAGE
            }
        }
    }

    /** 所有者の資格情報でマイグレーションする。パスワードはこのコマンドにだけ渡す。 */
    fun migrate(env: Map<String, String>): Int =
        when (val inputs = migrateInputs(env)) {
            is Result.Err -> {
                USAGE.also { logger.error("migrate の設定が不正です: {}", inputs.error.message) }
            }

            is Result.Ok -> {
                val (config, password) = inputs.value
                ownerDataSource(config, password).use {
                    when (val migrated = ShippingSchema.migrate(it, config.appUser, config.cdcUser)) {
                        is Result.Ok -> OK.also { logger.info("マイグレーションが終わりました") }
                        is Result.Err -> FAILED.also { logger.error("マイグレーションに失敗しました: {}", migrated.error.message) }
                    }
                }
            }
        }

    private fun migrateInputs(env: Map<String, String>): Result<Pair<ShippingConfig, Secret>, ValidationError> =
        ShippingConfig.fromEnvironment(env).flatMap { config ->
            when (val secret = EnvSecretProvider(env).get(ShippingConfig.OWNER_PASSWORD)) {
                is Result.Ok -> ok(config to secret.value)
                is Result.Err -> err(ValidationError.of(ShippingConfig.OWNER_PASSWORD.value, secret.error.message))
            }
        }

    private fun ownerDataSource(
        config: ShippingConfig,
        password: Secret,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.ownerUser
                this.password = password.reveal()
                // Flyway は履歴の管理とマイグレーションの実行に、接続を 2 本以上使う(1 本だと接続を待って失敗する)
                maximumPoolSize = MIGRATE_POOL_SIZE
                poolName = "shipping-migrate"
            },
        )
}
