package io.eia.fileexchange.app

import io.eia.fileexchange.application.port.inbound.HandOffUnsentUseCase
import io.eia.shared.kernel.Result
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.days

/** サブコマンド `handoff-unsent [--days <日数>] [--execute]`(P12 ⑦b の切り替えの 1 回きりの移行。ADR-0032 §8)。 */
internal object HandoffUnsentCommand {
    private const val DEFAULT_UNSENT_DAYS = 14
    private const val MAX_UNSENT_DAYS = 366
    private val logger = LoggerFactory.getLogger(HandoffUnsentCommand::class.java)

    /**
     * 切り替え(P12 ⑦b)の 1 回きりの移行。直接送っていた頃に archive に収め、送り終えていないファイル([days] 日以内)を、受け渡しの場所に写す。
     * 既定は dry-run(対象を表示するだけ)。`--execute` で写す。手順は docs/runbooks/file-exchange.md「取引先への送信の切り替え」。
     */
    @Suppress("ReturnCount") // 引数の誤りのそれぞれで返す
    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        val execute = "--execute" in args
        val rest = args - "--execute"
        val days =
            when {
                rest.isEmpty() -> {
                    DEFAULT_UNSENT_DAYS
                }

                rest.size == 2 && rest[0] == "--days" -> {
                    rest[1].toIntOrNull()?.takeIf { it in 1..MAX_UNSENT_DAYS }
                        ?: return FileExchangeCommands.usage()
                }

                else -> {
                    return FileExchangeCommands.usage()
                }
            }
        val since =
            kotlin.time.Clock.System
                .now() - days.days
        return FileExchangeCommands.withWired(env) { koin ->
            when (val report = runBlocking { koin.get<HandOffUnsentUseCase>()(since, execute) }) {
                is Result.Ok -> {
                    val value = report.value
                    value.candidates.forEach { logger.info("送り終えていないファイル: {}", it) }
                    value.failed.forEach { (name, error) -> logger.error("{} を写せません({})", name, error.code) }
                    if (execute) {
                        logger.info("受け渡しの場所に写しました({} 件中 {} 件。失敗 {} 件)", value.candidates.size, value.handedOff.size, value.failed.size)
                    } else {
                        logger.info("dry-run です。{} 件を写すには --execute を付けてください", value.candidates.size)
                    }
                    if (value.failed.isEmpty()) FileExchangeCommands.OK else FileExchangeCommands.FAILED
                }

                is Result.Err -> {
                    FileExchangeCommands.FAILED.also { logger.error("送り終えていないファイルを探せません: {}", report.error.message) }
                }
            }
        }
    }
}
