package io.eia.batchetl.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.batchetl.adapters.kafka.KafkaIngestPositions
import io.eia.batchetl.adapters.persistence.BatchEtlSchema
import io.eia.batchetl.adapters.persistence.JdbcRawOrderStore
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.ReconcileOutcome
import io.eia.platform.batch.BatchRunner
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.RunMode
import io.eia.platform.batch.RunOutcome
import io.eia.platform.batch.jdbc.JdbcBatchStore
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
import kotlin.time.Clock

/** サブコマンドの振り分けと終了コード(0: 成功 / 1: 実行の失敗 / 2: 使い方・設定の誤り)。 */
internal object BatchEtlCommands {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2

    /** `reconcile` で、比べ直しても一致しない日があった。 */
    const val DRIFT = 3
    private const val MIGRATE_POOL_SIZE = 3
    private const val USAGE_TEXT =
        "使い方: batch-etl migrate | serve | run <yyyy-MM-dd> [--restart] | reconcile <yyyy-MM-dd> [--restart] | " +
            "sales-file <yyyy-MM-dd> [--restart] | skip-lost-events [--confirm]"

    /** serve・run の環境にあってはならない変数(所有者のパスワード。ADR-0024 §2)。 */
    val FORBIDDEN_FOR_APP: List<String> =
        listOf(BatchEtlConfig.OWNER_PASSWORD.value, BatchEtlConfig.OWNER_PASSWORD.value + SecretName.FILE_SUFFIX)

    private val logger = LoggerFactory.getLogger(BatchEtlCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int =
        when (args.firstOrNull()) {
            "migrate" -> migrate(env)
            "serve" -> withoutOwnerPassword(env) { serve(env) }
            "run" -> withoutOwnerPassword(env) { runOnce(args.drop(1), env, RunTarget.DAILY) }
            "reconcile" -> withoutOwnerPassword(env) { runOnce(args.drop(1), env, RunTarget.RECONCILE) }
            "sales-file" -> withoutOwnerPassword(env) { runOnce(args.drop(1), env, RunTarget.SALES_FILE) }
            "skip-lost-events" -> withoutOwnerPassword(env) { skipLostCommand(args.drop(1), env) }
            else -> USAGE.also { logger.error(USAGE_TEXT) }
        }

    private fun withoutOwnerPassword(
        env: Map<String, String>,
        block: () -> Int,
    ): Int {
        FORBIDDEN_FOR_APP.firstOrNull { env.containsKey(it) }?.let {
            logger.error("serve・run の環境に所有者のパスワード({})を渡さないでください", it)
            return USAGE
        }
        return block()
    }

    private fun serve(env: Map<String, String>): Int =
        when (val server = BatchEtlServer.start(env)) {
            is Result.Ok -> OK.also { server.value.awaitTermination() }
            is Result.Err -> USAGE.also { logger.error("起動できません: {}", server.error.message) }
        }

    /**
     * 日次の DAG(`run`)・週次の照合の DAG(`reconcile`)・日次の売上ファイルの DAG(`sales-file`)を、そのパーティション(日付)について
     * 1 回だけ実行する。成功したパーティションは何もしない(`--restart` なら最初からやり直す)。照合で一致しない日があれば [DRIFT]。
     * `sales-file` は、その日の日次の DAG が成功した後に実行する(mart がまだなければ失敗する)。
     */
    @Suppress("ReturnCount") // 引数・設定の誤りは、実行の前に返す
    private fun runOnce(
        args: List<String>,
        env: Map<String, String>,
        target: RunTarget,
    ): Int {
        val restart = args.drop(1) == listOf("--restart")
        val day = args.firstOrNull()?.let { BusinessDay.parse(it) }
        if (day !is Result.Ok || (args.size > 1 && !restart)) return USAGE.also { logger.error(USAGE_TEXT) }
        val wired =
            when (val result = BatchEtlServer.wire(env)) {
                is Result.Err -> return USAGE.also { logger.error("設定が不正です: {}", result.error.message) }
                is Result.Ok -> result.value
            }
        val (koin, runtime) = wired.second
        try {
            target.missingSettings(wired.first)?.let { return USAGE.also { _ -> logger.error(it) } }
            return execute(koin, day.value, if (restart) RunMode.RESTART else RunMode.RESUME, target)
        } finally {
            BatchEtlServer.close(koin, runtime)
        }
    }

    private fun execute(
        koin: org.koin.core.Koin,
        day: BusinessDay,
        mode: RunMode,
        target: RunTarget,
    ): Int {
        val reconcile = target == RunTarget.RECONCILE
        val qualifier =
            when (target) {
                RunTarget.DAILY -> DAILY
                RunTarget.RECONCILE -> RECONCILE
                RunTarget.SALES_FILE -> SALES_FILE
            }
        val dag = koin.get<DagDefinition>(qualifier)
        return when (val outcome = runBlocking { koin.get<BatchRunner>().run(dag, PartitionKey(day.toString()), mode = mode) }) {
            is Result.Err -> {
                FAILED.also { logger.error("{} の実行が失敗しました: {}", day, outcome.error.message) }
            }

            is Result.Ok -> {
                report(outcome.value)
                val drift =
                    reconcile && koin
                        .get<ReconcileMetrics>()
                        .lastReport
                        ?.days
                        ?.any { it.outcome == ReconcileOutcome.DRIFT } == true
                if (drift) DRIFT else OK
            }
        }
    }

    /**
     * 保持期間を過ぎて消えたイベントを飛ばし、取り込みを再開できるようにする(docs/runbooks/batch-rerun.md の「保持期間を超えた回復」)。
     * 既定は表示だけ。`--confirm` で取り込みの位置を書き換える。飛ばしたイベントの日は、週次の照合で DRIFT として見つかる。
     */
    @Suppress("ReturnCount") // 引数・設定の誤りは、実行の前に返す
    private fun skipLostCommand(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        if (args.isNotEmpty() && args != listOf("--confirm")) return USAGE.also { logger.error(USAGE_TEXT) }
        val wired =
            when (val result = BatchEtlServer.wire(env)) {
                is Result.Err -> return USAGE.also { logger.error("設定が不正です: {}", result.error.message) }
                is Result.Ok -> result.value
            }
        val (koin, runtime) = wired.second
        try {
            return SkipLostEvents.run(koin, confirm = args.isNotEmpty())
        } finally {
            BatchEtlServer.close(koin, runtime)
        }
    }

    private fun report(outcome: RunOutcome) {
        when (outcome) {
            is RunOutcome.AlreadySucceeded -> logger.info("{} はすでに成功しています(作り直すなら --restart)", outcome.run)
            is RunOutcome.Succeeded -> logger.info("{} の実行が成功しました(実行 {}、前回の成功で飛ばした {})", outcome.run, outcome.executed, outcome.skipped)
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
                    when (val migrated = BatchEtlSchema.migrate(it, config.appUser)) {
                        is Result.Ok -> OK.also { logger.info("マイグレーションが終わりました") }
                        is Result.Err -> FAILED.also { logger.error("マイグレーションに失敗しました: {}", migrated.error.message) }
                    }
                }
            }
        }

    private fun migrateInputs(env: Map<String, String>): Result<Pair<BatchEtlConfig, Secret>, ValidationError> =
        BatchEtlConfig.fromEnvironment(env).flatMap { config ->
            when (val secret = EnvSecretProvider(env).get(BatchEtlConfig.OWNER_PASSWORD)) {
                is Result.Ok -> ok(config to secret.value)
                is Result.Err -> err(ValidationError.of(BatchEtlConfig.OWNER_PASSWORD.value, secret.error.message))
            }
        }

    private fun ownerDataSource(
        config: BatchEtlConfig,
        password: Secret,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.ownerUser
                this.password = password.reveal()
                // Flyway は履歴の管理とマイグレーションの実行に、接続を 2 本以上使う
                maximumPoolSize = MIGRATE_POOL_SIZE
                poolName = "batch-etl-migrate"
            },
        )
}

/** `run`・`reconcile`・`sales-file` が実行する DAG。 */
private enum class RunTarget {
    DAILY,
    RECONCILE,
    SALES_FILE,
    ;

    /** この DAG に要る設定がなければ、その説明。 */
    fun missingSettings(config: BatchEtlConfig): String? =
        when {
            this == RECONCILE && config.reconcile == null -> "照合には ${BatchEtlConfig.GATEWAY_URL} と ${BatchEtlConfig.TOKEN_URL} が必要です"
            this == SALES_FILE && config.salesFile == null -> "売上ファイルには ${BatchEtlConfig.FILE_S3_ENDPOINT} が必要です"
            else -> null
        }
}
