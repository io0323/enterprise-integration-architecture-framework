package io.eia.platform.messagingkafka

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.io.PrintStream
import kotlin.time.Instant

/**
 * DLQ の Replay の CLI の共通の部分(引数・表示・終了コード。ADR-0028 §6)。`tools/dlq-replay`(元のトピックに戻す)と、
 * サービスの再処理のコマンド(例 iot-bridge の `reprocess-dlq`。ADR-0039 §4)が同じ引数と表示を使う。値を取る引数は `--name value` の形。
 *
 * | 引数 | 意味 |
 * |---|---|
 * | `--topic <dlq>` | DLQ のトピック(`{topic}.dlq`)。DLQ が決まっているコマンドでは指定しない |
 * | `--limit <n>` | 必須。処理する件数の上限(1〜1000) |
 * | `--execute` | 送る。付けなければ dry-run(一覧だけ) |
 * | `--reason <code>` / `--type <ce_type>` / `--key <key>` | DLQ の原因・イベントの種類・キーで絞る |
 * | `--partition <n>` / `--from-offset <n>` / `--to-offset <n>` | DLQ の位置で絞る(オフセットは含む) |
 * | `--failed-from <ISO 8601>` / `--failed-to <ISO 8601>` | DLQ に入った時刻で絞る(from は含み、to は含まない) |
 * | `--exclude-replayed` | 一度戻して再び DLQ に入ったものを除く |
 *
 * 終了コード: [OK] 0 = 終えた(dry-run、またはすべての対象を処理した)/ [PARTIAL] 1 = 処理できなかった対象がある / [FAILED] 2 = 実行できない。
 */
public object ReplayCommandLine {
    public const val OK: Int = 0
    public const val PARTIAL: Int = 1
    public const val FAILED: Int = 2

    /** 条件の引数の説明(使い方の表示に含める)。 */
    public val CONDITIONS: String =
        """
        条件: --reason <code> --type <ce_type> --key <key> --partition <n> --from-offset <n> --to-offset <n>
              --failed-from <ISO 8601> --failed-to <ISO 8601> --exclude-replayed
        """.trimIndent()

    private const val TOPIC = "--topic"
    private val FLAGS = setOf("--execute", "--exclude-replayed")
    private val OPTIONS =
        setOf(
            "--limit",
            "--reason",
            "--type",
            "--key",
            "--partition",
            "--from-offset",
            "--to-offset",
            "--failed-from",
            "--failed-to",
        )

    /**
     * 解析した引数。
     *
     * @property options [parse] の `extraOptions` で受け付けた引数の値(例 `--bootstrap`)
     */
    public data class Arguments(
        public val request: ReplayRequest,
        public val options: Map<String, String>,
    )

    /**
     * @param deadLetterTopic DLQ が決まっているコマンドの DLQ。渡したときは `--topic` を受け付けない
     * @param extraOptions コマンドごとに足す、値を取る引数(例 `--bootstrap`)
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // 引数の項目ごとに、最初の誤りで返す
    public fun parse(
        args: List<String>,
        deadLetterTopic: String? = null,
        extraOptions: Set<String> = emptySet(),
    ): Result<Arguments, String> {
        val options = OPTIONS + extraOptions + if (deadLetterTopic == null) setOf(TOPIC) else emptySet()
        val values = mutableMapOf<String, String>()
        val flags = mutableSetOf<String>()
        var index = 0
        while (index < args.size) {
            val name = args[index]
            when (name) {
                in FLAGS -> {
                    flags += name
                }

                in options -> {
                    val value = args.getOrNull(index + 1) ?: return err("$name の値がありません")
                    if (values.put(name, value) != null) return err("$name が 2 回あります")
                    index++
                }

                TOPIC -> {
                    return err("$TOPIC は指定できません(このコマンドの DLQ は $deadLetterTopic)")
                }

                else -> {
                    return err("知らない引数です: $name")
                }
            }
            index++
        }
        val topic = deadLetterTopic ?: values[TOPIC] ?: return err("$TOPIC(DLQ のトピック)がありません")
        val limit = values["--limit"]?.toIntOrNull() ?: return err("--limit(処理する件数の上限)を整数で指定してください")
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
            Arguments(
                ReplayRequest(topic, filter, limit, execute = "--execute" in flags),
                values.filterKeys { it in extraOptions },
            ),
        )
    }

    /** 結果の一覧と件数を出す。DLQ の位置・キー・`ce_id`・原因を出し、値は出さない。 */
    public fun print(
        report: ReplayReport,
        out: PrintStream,
    ) {
        val mode = if (report.executed) "execute" else "dry-run(送っていない。送るには --execute)"
        out.println("DLQ: ${report.deadLetterTopic} → ${report.sourceTopic}($mode)")
        out.println("partition\toffset\tkey\tce_id\tce_type\treason\tfailed_at\treplayed_before\toutcome")
        report.entries.forEach { e ->
            out.println(
                listOf(e.partition, e.offset, e.key, e.ceId, e.ceType, e.reason, e.failedAt, e.replayedBefore, e.outcome)
                    .joinToString("\t") { it?.toString() ?: "-" },
            )
        }
        out.println(
            "読んだ件数 ${report.scanned} / 条件に合った件数 ${report.matched} / 対象 ${report.entries.size}" +
                "(上限で除いた件数 ${report.truncated})/ " +
                ReplayOutcome.entries.joinToString(" / ") { "${it.name} ${report.count(it)}" },
        )
        if (report.truncated > 0) out.println("上限を超えた分は対象にしていません。結果を確かめてから、もう一度実行してください")
    }

    /** dry-run の見込み(PLANNED)と処理できたもの(REPLAYED)だけなら [OK]、ほかがあれば [PARTIAL]。 */
    public fun exitCode(report: ReplayReport): Int {
        val done = setOf(ReplayOutcome.PLANNED, ReplayOutcome.REPLAYED)
        return if (report.entries.all { it.outcome in done }) OK else PARTIAL
    }

    private inline fun <T> Result<T, String>.orReturn(onError: (String) -> Nothing): T =
        when (this) {
            is Result.Ok -> value
            is Result.Err -> onError(error)
        }
}
