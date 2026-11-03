package io.eia.bffgraphql.app

import io.eia.shared.kernel.Result
import org.slf4j.LoggerFactory

/** サブコマンドの振り分けと終了コード(0: 成功 / 2: 使い方・設定の誤り)。 */
internal object BffCommands {
    const val OK = 0
    const val USAGE = 2
    private val logger = LoggerFactory.getLogger(BffCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        if (args.firstOrNull() != "serve") {
            logger.error("使い方: bff-graphql serve")
            return USAGE
        }
        return when (val started = BffServer.start(env)) {
            is Result.Ok -> {
                started.value.awaitTermination()
                OK
            }

            is Result.Err -> {
                started.error.violations.forEach { logger.error("設定の誤り: {}({})", it.field, it.reason) }
                USAGE
            }
        }
    }
}
