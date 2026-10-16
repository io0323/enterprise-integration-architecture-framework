package io.eia.fileexchange.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.fileexchange.adapters.persistence.FileExchangeSchema
import io.eia.fileexchange.application.port.inbound.SweepInboxUseCase
import io.eia.platform.batch.BatchRunner
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.RunMode
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/** サブコマンドの振り分けと終了コード(0: 成功 / 1: 実行の失敗 / 2: 使い方・設定の誤り)。 */
internal object FileExchangeCommands {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val MIGRATE_POOL_SIZE = 3
    private const val USAGE_TEXT = "使い方: file-exchange migrate | serve | receive <ファイルの名前(拡張子なし)> [--restart] | sweep"
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /** serve・receive・sweep の環境にあってはならない変数(所有者のパスワード。ADR-0024 §2)。 */
    val FORBIDDEN_FOR_APP: List<String> =
        listOf(FileExchangeConfig.OWNER_PASSWORD.value, FileExchangeConfig.OWNER_PASSWORD.value + SecretName.FILE_SUFFIX)

    private val logger = LoggerFactory.getLogger(FileExchangeCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int =
        when (args.firstOrNull()) {
            "migrate" -> migrate(env)
            "serve" -> withoutOwnerPassword(env) { serve(env) }
            "receive" -> withoutOwnerPassword(env) { receive(args.drop(1), env) }
            "sweep" -> withoutOwnerPassword(env) { if (args.size == 1) sweep(env) else usage() }
            else -> usage()
        }

    private fun usage(): Int = USAGE.also { logger.error(USAGE_TEXT) }

    private fun withoutOwnerPassword(
        env: Map<String, String>,
        block: () -> Int,
    ): Int {
        FORBIDDEN_FOR_APP.firstOrNull { env.containsKey(it) }?.let {
            logger.error("serve・receive・sweep の環境に所有者のパスワード({})を渡さないでください", it)
            return USAGE
        }
        return block()
    }

    private fun serve(env: Map<String, String>): Int =
        when (val server = FileExchangeServer.start(env)) {
            is Result.Ok -> OK.also { server.value.awaitTermination() }
            is Result.Err -> USAGE.also { logger.error("起動できません: {}", server.error.message) }
        }

    /** 1 つのファイルの受け取りを 1 回だけ実行する(運用者の再実行。`--restart` なら最初からやり直す)。 */
    private fun receive(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        val restart = args.drop(1) == listOf("--restart")
        val name = args.firstOrNull()?.takeIf { NAME.matches(it) }
        val extraArguments = args.size > 1 && !restart
        if (name == null || extraArguments) return usage()
        return withWired(env) { koin ->
            val dag = koin.get<DagDefinition>()
            val mode = if (restart) RunMode.RESTART else RunMode.RESUME
            when (val outcome = runBlocking { koin.get<BatchRunner>().run(dag, PartitionKey(name), mode = mode) }) {
                is Result.Ok -> OK.also { logger.info("{} を受け取りました({})", name, outcome.value) }
                is Result.Err -> FAILED.also { logger.error("{} の受け取りが失敗しました: {}", name, outcome.error.message) }
            }
        }
    }

    /** 受け口を 1 回だけ見回る。 */
    private fun sweep(env: Map<String, String>): Int =
        withWired(env) { koin ->
            when (val report = runBlocking { koin.get<SweepInboxUseCase>()() }) {
                is Result.Ok -> OK.also { logger.info("受け口を見回りました(移した {} 件、残り {} 件)", report.value.moved.size, report.value.waiting) }
                is Result.Err -> FAILED.also { logger.error("受け口を見回れません: {}", report.error.message) }
            }
        }

    private fun withWired(
        env: Map<String, String>,
        block: (org.koin.core.Koin) -> Int,
    ): Int =
        when (val wired = FileExchangeServer.wire(env)) {
            is Result.Err -> {
                USAGE.also { logger.error("設定が不正です: {}", wired.error.message) }
            }

            is Result.Ok -> {
                val (koin, runtime) = wired.value.second
                try {
                    block(koin)
                } finally {
                    FileExchangeServer.close(koin, runtime)
                }
            }
        }

    fun migrate(env: Map<String, String>): Int =
        when (val inputs = migrateInputs(env)) {
            is Result.Err -> {
                USAGE.also { logger.error("設定が不正です: {}", inputs.error.message) }
            }

            is Result.Ok -> {
                val (config, password) = inputs.value
                ownerDataSource(config, password).use {
                    when (val migrated = FileExchangeSchema.migrate(it, config.appUser)) {
                        is Result.Ok -> OK.also { logger.info("マイグレーションが終わりました") }
                        is Result.Err -> FAILED.also { logger.error("マイグレーションに失敗しました: {}", migrated.error.message) }
                    }
                }
            }
        }

    private fun migrateInputs(env: Map<String, String>): Result<Pair<FileExchangeConfig, Secret>, ValidationError> =
        FileExchangeConfig.fromEnvironment(env).flatMap { config ->
            when (val secret = EnvSecretProvider(env).get(FileExchangeConfig.OWNER_PASSWORD)) {
                is Result.Ok -> ok(config to secret.value)
                is Result.Err -> err(ValidationError.of(FileExchangeConfig.OWNER_PASSWORD.value, secret.error.message))
            }
        }

    private fun ownerDataSource(
        config: FileExchangeConfig,
        password: Secret,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.ownerUser
                this.password = password.reveal()
                // Flyway は履歴の管理とマイグレーションの実行に、接続を 2 本以上使う
                maximumPoolSize = MIGRATE_POOL_SIZE
                poolName = "file-exchange-migrate"
            },
        )
}
