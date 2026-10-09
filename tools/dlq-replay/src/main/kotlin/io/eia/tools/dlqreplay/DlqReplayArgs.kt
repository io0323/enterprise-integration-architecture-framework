package io.eia.tools.dlqreplay

import io.eia.platform.messagingkafka.DeadLetterReplayer
import io.eia.platform.messagingkafka.ReplayFilter
import io.eia.platform.messagingkafka.ReplayRequest
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Instant

/**
 * コマンドラインの引数。値を取る引数は `--name value` の形。
 *
 * | 引数 | 意味 |
 * |---|---|
 * | `--topic <dlq>` | 必須。DLQ のトピック(`{topic}.dlq`) |
 * | `--limit <n>` | 必須。戻す件数の上限(1〜1000) |
 * | `--execute` | 送る。付けなければ dry-run(一覧だけ) |
 * | `--reason <code>` / `--type <ce_type>` / `--key <key>` | DLQ の原因・イベントの種類・キーで絞る |
 * | `--partition <n>` / `--from-offset <n>` / `--to-offset <n>` | DLQ の位置で絞る(オフセットは含む) |
 * | `--failed-from <ISO 8601>` / `--failed-to <ISO 8601>` | DLQ に入った時刻で絞る(from は含み、to は含まない) |
 * | `--exclude-replayed` | 一度戻して再び DLQ に入ったものを除く |
 * | `--bootstrap <host:port>` | Kafka(既定はローカル基盤のホスト用のリスナー `localhost:19092`) |
 */
internal data class DlqReplayArgs(
    val request: ReplayRequest,
    val bootstrapServers: String,
) {
    companion object {
        const val DEFAULT_BOOTSTRAP = "localhost:19092"
        private val FLAGS = setOf("--execute", "--exclude-replayed")
        private val OPTIONS =
            setOf(
                "--topic",
                "--limit",
                "--reason",
                "--type",
                "--key",
                "--partition",
                "--from-offset",
                "--to-offset",
                "--failed-from",
                "--failed-to",
                "--bootstrap",
            )

        val USAGE =
            """
            使い方: make dlq-replay ARGS="--topic <{topic}.dlq> --limit <1-${ReplayRequest.MAX_LIMIT}> [--execute] [条件]"
              既定は dry-run(対象の一覧だけ)。送るのは --execute を付けたときだけ。
              条件: --reason <code> --type <ce_type> --key <key> --partition <n> --from-offset <n> --to-offset <n>
                    --failed-from <ISO 8601> --failed-to <ISO 8601> --exclude-replayed
              手順: docs/runbooks/event-dlq-replay.md
            """.trimIndent()

        @Suppress("ReturnCount", "CyclomaticComplexMethod") // 引数の項目ごとに、最初の誤りで返す
        fun parse(args: List<String>): Result<DlqReplayArgs, String> {
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var index = 0
            while (index < args.size) {
                val name = args[index]
                when (name) {
                    in FLAGS -> {
                        flags += name
                    }

                    in OPTIONS -> {
                        val value = args.getOrNull(index + 1) ?: return err("$name の値がありません")
                        if (values.put(name, value) != null) return err("$name が 2 回あります")
                        index++
                    }

                    else -> {
                        return err("知らない引数です: $name")
                    }
                }
                index++
            }
            val topic = values["--topic"] ?: return err("--topic(DLQ のトピック)がありません")
            val limit = values["--limit"]?.toIntOrNull() ?: return err("--limit(戻す件数の上限)を整数で指定してください")
            if (limit !in 1..ReplayRequest.MAX_LIMIT) return err("--limit は 1〜${ReplayRequest.MAX_LIMIT} にしてください")

            fun int(name: String): Result<Int?, String> =
                values[name]?.let { v -> v.toIntOrNull()?.takeIf { it >= 0 }?.let { ok(it) } ?: err("$name は 0 以上の整数にしてください") } ?: ok(null)

            fun long(name: String): Result<Long?, String> =
                values[name]?.let { v -> v.toLongOrNull()?.takeIf { it >= 0 }?.let { ok(it) } ?: err("$name は 0 以上の整数にしてください") } ?: ok(null)

            fun instant(name: String): Result<Instant?, String> =
                values[name]?.let { v ->
                    DeadLetterReplayer.parseInstant(v)?.let { ok(it) }
                        ?: err("$name は ISO 8601 の時刻にしてください(例 2026-10-09T01:00:00Z)")
                } ?: ok(null)
            val partition = int("--partition").orReturn { return err(it) }
            val fromOffset = long("--from-offset").orReturn { return err(it) }
            val toOffset = long("--to-offset").orReturn { return err(it) }
            val failedFrom = instant("--failed-from").orReturn { return err(it) }
            val failedTo = instant("--failed-to").orReturn { return err(it) }
            if (fromOffset != null && toOffset != null && fromOffset > toOffset) return err("--from-offset は --to-offset 以下にしてください")
            if (failedFrom != null && failedTo != null && failedFrom >= failedTo) return err("--failed-from は --failed-to より前にしてください")
            val filter =
                ReplayFilter(
                    reason = values["--reason"],
                    ceType = values["--type"],
                    key = values["--key"],
                    partition = partition,
                    fromOffset = fromOffset,
                    toOffset = toOffset,
                    failedFrom = failedFrom,
                    failedTo = failedTo,
                    includeReplayed = "--exclude-replayed" !in flags,
                )
            return ok(
                DlqReplayArgs(
                    ReplayRequest(topic, filter, limit, execute = "--execute" in flags),
                    values["--bootstrap"] ?: DEFAULT_BOOTSTRAP,
                ),
            )
        }

        private inline fun <T> Result<T, String>.orReturn(onError: (String) -> Nothing): T =
            when (this) {
                is Result.Ok -> value
                is Result.Err -> onError(error)
            }
    }
}
