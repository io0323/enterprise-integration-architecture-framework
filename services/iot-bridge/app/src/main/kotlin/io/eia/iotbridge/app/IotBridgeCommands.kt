package io.eia.iotbridge.app

import io.eia.shared.kernel.Result
import org.slf4j.LoggerFactory
import java.io.PrintStream

/**
 * サブコマンドの振り分けと終了コード。order-service と同じ形(ADR-0024 §2)。
 *
 * - `serve`: MQTT のテレメトリを購読して Kafka に発行し、終了の合図(SIGTERM)まで動く(0: 終えた / 2: 設定の誤り)。
 * - `reprocess-dlq`: DLQ をデコードからやり直して発行する([ReprocessDlqCommand]。0 / 1 / 2)。
 */
internal object IotBridgeCommands {
    const val OK = 0
    const val USAGE = 2
    private val logger = LoggerFactory.getLogger(IotBridgeCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
        out: PrintStream,
    ): Int =
        when (args.firstOrNull()) {
            "serve" -> {
                serve(env)
            }

            "reprocess-dlq" -> {
                ReprocessDlqCommand().run(args.drop(1), env, out)
            }

            else -> {
                logger.error("使い方: iot-bridge serve | reprocess-dlq --limit <n> [--execute] [条件]")
                USAGE
            }
        }

    private fun serve(env: Map<String, String>): Int =
        when (val server = IotBridgeServer.start(env)) {
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
