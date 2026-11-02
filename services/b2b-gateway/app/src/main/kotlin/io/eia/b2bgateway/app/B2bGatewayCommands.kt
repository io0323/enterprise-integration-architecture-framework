package io.eia.b2bgateway.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.b2bgateway.adapters.codes.CsvCodeFile
import io.eia.b2bgateway.adapters.persistence.B2bGatewaySchema
import io.eia.b2bgateway.application.port.inbound.ImportCodeTableUseCase
import io.eia.b2bgateway.application.port.inbound.InvalidCodeTable
import io.eia.b2bgateway.domain.codes.CodeKind
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** サブコマンドの振り分けと終了コード(0: 成功 / 1: 実行の失敗 / 2: 使い方・設定・入力の誤り)。 */
internal object B2bGatewayCommands {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val MIGRATE_POOL_SIZE = 3
    private const val USAGE_TEXT =
        "使い方: b2b-gateway migrate | serve | codes import <取引先> <buyers|delivery-parties|items> <CSV のファイル> --operator <運用者>"
    private val OPERATOR = Regex("^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")

    /** serve・codes の環境にあってはならない変数(所有者のパスワード。ADR-0024 §2)。 */
    val FORBIDDEN_FOR_APP: List<String> =
        listOf(B2bGatewayConfig.OWNER_PASSWORD.value, B2bGatewayConfig.OWNER_PASSWORD.value + SecretName.FILE_SUFFIX)

    private val logger = LoggerFactory.getLogger(B2bGatewayCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int =
        when (args.firstOrNull()) {
            "migrate" -> if (args.size == 1) migrate(env) else usage()
            "serve" -> withoutOwnerPassword(env) { if (args.size == 1) serve(env) else usage() }
            "codes" -> withoutOwnerPassword(env) { codes(args.drop(1), env) }
            else -> usage()
        }

    private fun usage(): Int = USAGE.also { logger.error(USAGE_TEXT) }

    private fun withoutOwnerPassword(
        env: Map<String, String>,
        block: () -> Int,
    ): Int {
        FORBIDDEN_FOR_APP.firstOrNull { env.containsKey(it) }?.let {
            logger.error("serve・codes の環境に所有者のパスワード({})を渡さないでください", it)
            return USAGE
        }
        return block()
    }

    private fun serve(env: Map<String, String>): Int =
        when (val server = B2bGatewayServer.start(env)) {
            is Result.Ok -> OK.also { server.value.awaitTermination() }
            is Result.Err -> USAGE.also { logger.error("起動できません: {}", server.error.message) }
        }

    /**
     * `codes import <取引先> <種類> <ファイル> --operator <運用者>`。ファイルを検査し、誤りがあれば何も変えずに行の番号・列の名前・理由を表示する
     * (値は表示しない)。
     */
    @Suppress("ReturnCount") // 引数とファイルの読み出しの誤りのそれぞれで返す
    private fun codes(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        val request = CodesRequest.parse(args) ?: return usage()
        val bytes = request.read() ?: return USAGE
        return withWired(env) { koin ->
            val imported =
                CsvCodeFile
                    .read(request.partnerId, request.kind, bytes, request.operator)
                    .flatMap { runBlocking { koin.get<ImportCodeTableUseCase>()(it) } }
            when (imported) {
                is Result.Ok -> {
                    val result = imported.value
                    OK.also {
                        logger.info(
                            "取引先 {} の {} を入れ替えました(版 {}、{} 行)",
                            request.partnerId,
                            request.kind.label,
                            result.version,
                            result.rows,
                        )
                    }
                }

                is Result.Err -> {
                    failed(imported.error)
                }
            }
        }
    }

    private fun failed(error: DomainError): Int {
        if (error !is InvalidCodeTable) return FAILED.also { logger.error("対応表を入れ替えられません: {}", error.message) }
        error.violations.forEach { logger.error("{} 行目{}: {}", it.row, it.column?.let { c -> " $c" }.orEmpty(), it.reason) }
        return USAGE.also { logger.error("ファイルに誤りがあるので、何も変えていません({} 件)", error.violations.size) }
    }

    /** `codes import` の引数。 */
    private class CodesRequest(
        val partnerId: String,
        val kind: CodeKind,
        val file: Path,
        val operator: String,
    ) {
        /** ファイルを読む。大きすぎる・読めなければ、ログに出して null。 */
        fun read(): ByteArray? =
            try {
                if (Files.size(file) > CsvCodeFile.MAX_BYTES) {
                    null.also { logger.error("ファイルが大きすぎます") }
                } else {
                    Files.readAllBytes(file)
                }
            } catch (_: IOException) {
                null.also { logger.error("ファイルを読めません") }
            }

        companion object {
            // codes import <取引先> <種類> <ファイル> --operator <運用者>
            private const val ARGS = 6
            private const val OPERATOR_FLAG = 4

            fun parse(args: List<String>): CodesRequest? {
                if (args.size != ARGS || args[0] != "import" || args[OPERATOR_FLAG] != "--operator") return null
                val (partnerId, kindLabel, file) = args.subList(1, OPERATOR_FLAG)
                val operator = args[OPERATOR_FLAG + 1]
                val kind = CodeKind.of(kindLabel)?.takeIf { OPERATOR.matches(operator) }
                return kind?.let { CodesRequest(partnerId, it, Path.of(file), operator) }
            }
        }
    }

    private fun withWired(
        env: Map<String, String>,
        block: (org.koin.core.Koin) -> Int,
    ): Int =
        when (val wired = B2bGatewayServer.wire(env, serve = false)) {
            is Result.Err -> {
                USAGE.also { logger.error("設定が不正です: {}", wired.error.message) }
            }

            is Result.Ok -> {
                val (koin, runtime) = wired.value.second
                try {
                    block(koin)
                } finally {
                    B2bGatewayServer.close(koin, runtime)
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
                    when (val migrated = B2bGatewaySchema.migrate(it, config.appUser)) {
                        is Result.Ok -> OK.also { logger.info("マイグレーションが終わりました") }
                        is Result.Err -> FAILED.also { logger.error("マイグレーションに失敗しました: {}", migrated.error.message) }
                    }
                }
            }
        }

    private fun migrateInputs(env: Map<String, String>): Result<Pair<B2bGatewayConfig, Secret>, ValidationError> =
        B2bGatewayConfig.fromEnvironment(env).flatMap { config ->
            when (val secret = EnvSecretProvider(env).get(B2bGatewayConfig.OWNER_PASSWORD)) {
                is Result.Ok -> ok(config to secret.value)
                is Result.Err -> err(ValidationError.of(B2bGatewayConfig.OWNER_PASSWORD.value, secret.error.message))
            }
        }

    private fun ownerDataSource(
        config: B2bGatewayConfig,
        password: Secret,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.ownerUser
                this.password = password.reveal()
                // Flyway は履歴の管理とマイグレーションの実行に、接続を 2 本以上使う
                maximumPoolSize = MIGRATE_POOL_SIZE
                poolName = "b2b-gateway-migrate"
            },
        )
}
