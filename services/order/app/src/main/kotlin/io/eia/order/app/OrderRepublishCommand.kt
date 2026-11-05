package io.eia.order.app

import io.eia.order.application.port.inbound.RepublishOrderEventsCommand
import io.eia.order.application.port.inbound.RepublishOrderEventsUseCase
import io.eia.order.application.port.inbound.RequestedBy
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.Result
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Instant

/**
 * `order-service republish --from <時刻> --to <時刻> --reason <理由> --operator <運用者> [--run-id <ID>] [--execute]`(ADR-0046)。
 * 既定は dry-run で、対象の件数を数えるだけ。`--execute` で Outbox に書く。アプリのロールの資格情報で動く(serve と同じ)。
 */
internal object OrderRepublishCommand {
    private val logger = LoggerFactory.getLogger(OrderRepublishCommand::class.java)
    private val TOKEN = Regex("^[a-zA-Z0-9._-]{1,64}$")
    private const val USAGE_TEXT =
        "使い方: order-service republish --from <RFC 3339> --to <RFC 3339> --reason <理由> --operator <運用者> [--run-id <ID>] [--execute]"

    internal data class Options(
        val from: Instant,
        val to: Instant,
        val reason: String,
        val operator: String,
        val runId: String,
        val execute: Boolean,
    )

    @Suppress("ReturnCount") // 引数と設定の誤りのそれぞれで返す
    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        val options =
            parse(args) ?: run {
                logger.error(USAGE_TEXT)
                return OrderCommands.USAGE
            }
        val inputs =
            when (val read = OrderServer.serveInputs(env)) {
                is Result.Err -> {
                    logger.error("設定が不正です: {}", read.error.message)
                    return OrderCommands.USAGE
                }

                is Result.Ok -> {
                    read.value
                }
            }
        val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "order-service") + env
        val observability =
            when (val config = ObservabilityConfig.fromEnvironment(observabilityEnv)) {
                is Result.Err -> {
                    logger.error("設定が不正です: {}", config.error.message)
                    return OrderCommands.USAGE
                }

                is Result.Ok -> {
                    config.value
                }
            }
        val runtime = Observability.init(observability)
        val koin = koinApplication { modules(orderModule(inputs.first, inputs.second, runtime, EnvSecretProvider(env))) }.koin
        return try {
            runBlocking { execute(options, koin.get(), koin.get()) }
        } finally {
            koin.close()
            runtime.close()
        }
    }

    private suspend fun execute(
        options: Options,
        schemaIds: SchemaIdBook,
        republish: RepublishOrderEventsUseCase,
    ): Int {
        if (options.execute) {
            val resolved = schemaIds.resolve()
            if (resolved is Result.Err) {
                logger.error("スキーマ ID を解決できません({})", resolved.error.code)
                return OrderCommands.FAILED
            }
        }
        val command =
            RepublishOrderEventsCommand(
                options.from,
                options.to,
                options.reason,
                options.runId,
                options.execute,
                RequestedBy(options.operator),
            )
        return when (val report = republish(command)) {
            is Result.Ok -> {
                val action = if (report.value.executed) "再発行しました" else "対象です(dry-run。--execute で再発行します)"
                println("run-id=${options.runId} orders=${report.value.orders} $action")
                OrderCommands.OK
            }

            is Result.Err -> {
                // 書いた分は確定している。同じ範囲でやり直してよい(受信側が業務のキーで重複を除く)
                logger.error("再発行に失敗しました({})。同じ範囲でやり直せます", report.error.code)
                OrderCommands.FAILED
            }
        }
    }

    /** 引数の誤りは null。理由・運用者・実行の ID は、ヘッダと監査に入るので決まった文字だけにする(業務のデータを入れない)。 */
    @Suppress("ReturnCount") // 誤りのそれぞれで返す
    internal fun parse(args: List<String>): Options? {
        val values = mutableMapOf<String, String>()
        var execute = false
        var index = 0
        while (index < args.size) {
            val name = args[index]
            when {
                name == "--execute" -> {
                    execute = true
                    index++
                }

                name in setOf("--from", "--to", "--reason", "--operator", "--run-id") && index + 1 < args.size -> {
                    values[name] = args[index + 1]
                    index += 2
                }

                else -> {
                    return null
                }
            }
        }
        val runId = values["--run-id"] ?: UUID.randomUUID().toString()
        if (!TOKEN.matches(runId)) return null
        return Options(
            from = values["--from"]?.let(Instant::parseOrNull) ?: return null,
            to = values["--to"]?.let(Instant::parseOrNull) ?: return null,
            reason = values["--reason"]?.takeIf(TOKEN::matches) ?: return null,
            operator = values["--operator"]?.takeIf(TOKEN::matches) ?: return null,
            runId = runId,
            execute = execute,
        )
    }
}
