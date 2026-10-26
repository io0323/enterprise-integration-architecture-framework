package io.eia.tools.dlqreplay

import io.eia.platform.messagingkafka.DeadLetterReplayer
import io.eia.platform.messagingkafka.ReplayCommandLine
import io.eia.shared.kernel.Result
import kotlinx.coroutines.runBlocking
import java.io.PrintStream

/**
 * DLQ の Replay(`make dlq-replay`。ADR-0028 §6。docs/runbooks/event-dlq-replay.md)。
 *
 * 終了コード:
 * - [OK] 0: 終えた(dry-run、またはすべての対象を戻した)
 * - [PARTIAL] 1: 戻せなかった対象がある(送信の失敗・元のトピックの記録の不一致)。もう一度実行してよい(受信側は冪等)
 * - [FAILED] 2: 実行できなかった(引数の誤り・拒否した DLQ・Kafka に接続できない)
 *
 * 出力には DLQ の位置・キー・`ce_id`・原因を出し、値は出さない。
 */
internal class DlqReplayCommand(
    private val connect: (bootstrapServers: String) -> DeadLetterReplayer = { DeadLetterReplayer.connect(it, CLIENT_ID) },
) {
    /** 実行して終了コードを返す。想定外の例外も [FAILED] にする(例外のメッセージは値を含みうるので、クラス名だけを出す)。 */
    @Suppress("TooGenericExceptionCaught") // 境界で全例外を終了コード 2 に変換するのがこの関数の責務
    fun run(
        args: List<String>,
        out: PrintStream,
    ): Int =
        try {
            replay(args, out)
        } catch (e: Exception) {
            out.println("実行できません: 想定外のエラー(${e::class.simpleName})")
            FAILED
        }

    private fun replay(
        args: List<String>,
        out: PrintStream,
    ): Int {
        val parsed =
            when (val result = DlqReplayArgs.parse(args)) {
                is Result.Ok -> {
                    result.value
                }

                is Result.Err -> {
                    out.println("引数: ${result.error}")
                    out.println(DlqReplayArgs.USAGE)
                    return FAILED
                }
            }
        return connect(parsed.bootstrapServers).use { replayer ->
            when (val result = runBlocking { replayer.replay(parsed.request) }) {
                is Result.Ok -> {
                    ReplayCommandLine.print(result.value, out)
                    ReplayCommandLine.exitCode(result.value)
                }

                is Result.Err -> {
                    out.println("実行できません: ${result.error.message}")
                    FAILED
                }
            }
        }
    }

    companion object {
        const val OK = ReplayCommandLine.OK
        const val PARTIAL = ReplayCommandLine.PARTIAL
        const val FAILED = ReplayCommandLine.FAILED
        private const val CLIENT_ID = "dlq-replay"
    }
}
