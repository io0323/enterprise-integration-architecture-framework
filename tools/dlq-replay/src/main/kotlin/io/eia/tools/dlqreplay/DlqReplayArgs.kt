package io.eia.tools.dlqreplay

import io.eia.platform.messagingkafka.ReplayCommandLine
import io.eia.platform.messagingkafka.ReplayRequest
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map

/**
 * コマンドラインの引数。DLQ・件数の上限・条件の引数は Replay の CLI で共通([ReplayCommandLine])。このコマンドだけの引数は次のとおり。
 *
 * | 引数 | 意味 |
 * |---|---|
 * | `--topic <dlq>` | 必須。DLQ のトピック(`{topic}.dlq`) |
 * | `--bootstrap <host:port>` | Kafka(既定はローカル基盤のホスト用のリスナー `localhost:19092`) |
 */
internal data class DlqReplayArgs(
    val request: ReplayRequest,
    val bootstrapServers: String,
) {
    companion object {
        const val DEFAULT_BOOTSTRAP = "localhost:19092"
        private const val BOOTSTRAP = "--bootstrap"

        val USAGE =
            """
            |使い方: make dlq-replay ARGS="--topic <{topic}.dlq> --limit <1-${ReplayRequest.MAX_LIMIT}> [--execute] [条件]"
            |  既定は dry-run(対象の一覧だけ)。送るのは --execute を付けたときだけ。
            |${ReplayCommandLine.CONDITIONS.prependIndent("  ")}
            |  手順: docs/runbooks/event-dlq-replay.md
            """.trimMargin()

        fun parse(args: List<String>): Result<DlqReplayArgs, String> =
            ReplayCommandLine.parse(args, extraOptions = setOf(BOOTSTRAP)).map {
                DlqReplayArgs(it.request, it.options[BOOTSTRAP] ?: DEFAULT_BOOTSTRAP)
            }
    }
}
