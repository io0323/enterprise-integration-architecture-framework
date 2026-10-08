package io.eia.legacysim.app

import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory

/** サブコマンドの振り分けと終了コード(0: 成功 / 1: 実行の失敗 / 2: 使い方・設定の誤り)。 */
internal object LegacySimCommands {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val MAX_COUNT = 10_000

    private val logger = LoggerFactory.getLogger(LegacySimCommands::class.java)

    val USAGE_TEXT: String =
        """
        使い方:
          legacy-sim migrate
          legacy-sim simulate seed <件数>       正常な受注を登録する(状態は受付)
          legacy-sim simulate advance <件数>    受付・引当済の受注の状態を 1 段進める
          legacy-sim simulate cancel <件数>     出荷前の受注を取り消す
          legacy-sim simulate delete <件数>     受注を物理削除する
          legacy-sim simulate anomaly <種類>    変換できない値を持つ受注を登録する(${Anomaly.entries.joinToString(" / ") { it.argument }})
        """.trimIndent()

    /** [output] には、simulate が変えた受注番号を 1 行ずつ渡す。 */
    fun run(
        args: List<String>,
        env: Map<String, String>,
        output: (String) -> Unit,
    ): Int =
        when (val prepared = prepare(args, env)) {
            is Result.Err -> {
                // 使い方は複数行のため、ログ(1 行の JSON)ではなく標準エラーに出す
                USAGE.also { System.err.println(prepared.error) }
            }

            is Result.Ok -> {
                val (action, config, dataSource) = prepared.value
                when (action) {
                    is Action.Migrate -> migrate(dataSource, config)
                    is Action.Simulate -> simulate(dataSource, action, output)
                }
            }
        }

    /** 引数・設定・パスワードを確かめ、接続先を作る(接続はしない)。誤りはログに出すメッセージで返す。 */
    private fun prepare(
        args: List<String>,
        env: Map<String, String>,
    ): Result<Triple<Action, LegacySimConfig, PGSimpleDataSource>, String> {
        val action = parse(args) ?: return err(USAGE_TEXT)
        return LegacySimConfig
            .fromEnvironment(env)
            .mapError { "設定が不正です: ${it.message}" }
            .flatMap { config ->
                val (user, name) =
                    if (action is Action.Migrate) {
                        config.ownerUser to LegacySimConfig.OWNER_PASSWORD
                    } else {
                        config.appUser to
                            LegacySimConfig.APP_PASSWORD
                    }
                EnvSecretProvider(env).get(name).mapError { "設定が不正です: ${it.message}" }.map { password ->
                    Triple(action, config, dataSource(config.dbUrl, user, password))
                }
            }
    }

    private fun dataSource(
        url: String,
        user: String,
        password: Secret,
    ): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(url)
            this.user = user
            this.password = password.reveal()
        }

    private fun migrate(
        dataSource: PGSimpleDataSource,
        config: LegacySimConfig,
    ): Int =
        when (val migrated = LegacySchema.migrate(dataSource, config.appUser, config.cdcUser, config.reconcileUser)) {
            is Result.Ok -> OK.also { logger.info("マイグレーションが終わりました") }
            is Result.Err -> FAILED.also { logger.error("マイグレーションに失敗しました: {}", migrated.error.message) }
        }

    private fun simulate(
        dataSource: PGSimpleDataSource,
        action: Action.Simulate,
        output: (String) -> Unit,
    ): Int =
        try {
            dataSource.connection.use { connection ->
                val app = LegacyJuchuApp(connection)
                val changed = action.perform(app)
                changed.forEach(output)
                logger.info("受注を {} 件変えました({})", changed.size, action.name)
                OK
            }
        } catch (e: java.sql.SQLException) {
            // SQL の例外のメッセージは値を含みうるため、SQLState だけを出す
            logger.error("受注を変えられません(SQLState {})", e.sqlState)
            FAILED
        }

    internal fun parse(args: List<String>): Action? =
        when (args.firstOrNull()) {
            "migrate" -> if (args.size == 1) Action.Migrate else null
            "simulate" -> parseSimulate(args.drop(1))
            else -> null
        }

    private fun parseSimulate(args: List<String>): Action.Simulate? {
        if (args.size != 2) return null
        val (name, value) = args
        val count = value.toIntOrNull()?.takeIf { it in 1..MAX_COUNT }
        return when (name) {
            "seed" -> count?.let { Action.Simulate(name) { app -> app.seed(it) } }
            "advance" -> count?.let { Action.Simulate(name) { app -> app.advance(it) } }
            "cancel" -> count?.let { Action.Simulate(name) { app -> app.cancel(it) } }
            "delete" -> count?.let { Action.Simulate(name) { app -> app.delete(it) } }
            "anomaly" -> Anomaly.of(value)?.let { kind -> Action.Simulate(name) { app -> listOf(app.anomaly(kind)) } }
            else -> null
        }
    }

    internal sealed interface Action {
        data object Migrate : Action

        class Simulate(
            val name: String,
            val perform: (LegacyJuchuApp) -> List<String>,
        ) : Action
    }
}
