package io.eia.fileexchange.application.usecase

import io.eia.fileexchange.application.port.inbound.SweepInboxUseCase
import io.eia.fileexchange.application.port.inbound.SweepReport
import io.eia.fileexchange.application.port.outbound.FileLocks
import io.eia.fileexchange.application.port.outbound.Inbox
import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.ReceiptState
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok

/**
 * 受け口の見回り。通常の処理(ファイルの到着の実行)と同じファイルを扱わないよう、次の 3 つで分ける(ADR-0032 §5):
 * - ファイルの名前ごとのロック(受け取りの実行と同じ advisory lock)を取れたものだけを扱う
 * - ロックの中で受け取りの記録を読み、「移し終えた」(STORED)名前だけを扱う。記録がない・移動中の名前は通常の処理のもの
 * - ロックの中で受け口のマニフェストを読み直し、なければ扱わない(受け取りの実行が移し終えた直後に一覧したもの)
 */
public class SweepInboxService(
    private val inbox: Inbox,
    private val files: ReceivedFiles,
    private val locks: FileLocks,
) : SweepInboxUseCase {
    @Suppress("ReturnCount") // 記録・受け口の読み取りの失敗で返す
    override suspend fun invoke(): Result<SweepReport, DomainError> {
        val moved = mutableListOf<ExtraArrival>()
        // 処理中のまま残っているもの(前の見回りが落ちた)を先に終える
        val pending =
            when (val found = files.movingExtras()) {
                is Result.Err -> return found
                is Result.Ok -> found.value
            }
        for (arrival in pending) {
            when (val resumed = locks.withLock(arrival.name) { finish(arrival) }) {
                is Result.Err -> return resumed
                is Result.Ok -> resumed.value?.let { moved += it }
            }
        }
        val waiting =
            when (val listed = inbox.waiting()) {
                is Result.Err -> return listed
                is Result.Ok -> listed.value
            }
        for (file in waiting) {
            when (val handled = locks.withLock(file.name) { extra(file.name) }) {
                is Result.Err -> return handled
                is Result.Ok -> handled.value?.let { moved += it }
            }
        }
        val remaining = waiting.filter { file -> moved.none { it.name == file.name } }
        return ok(SweepReport(moved, remaining.size, remaining.mapNotNull { it.createdAt }.minOrNull()))
    }

    /** ロックの中: 受け取り済みの名前で、マニフェストが今もあれば、送り直しか名前の使い回しとして移す。扱わなければ null。 */
    @Suppress("ReturnCount") // 扱わない・失敗のそれぞれで返す
    private suspend fun extra(name: String): Result<ExtraArrival?, DomainError> {
        val received =
            when (val found = files.find(name)) {
                is Result.Err -> return found
                is Result.Ok -> found.value
            }
        if (received?.state != ReceiptState.STORED) return ok(null)
        val file =
            when (val read = inbox.waiting(name)) {
                is Result.Err -> return read
                is Result.Ok -> read.value ?: return ok(null)
            }
        return when (val begun = files.beginExtra(name, file.sha256, ExtraArrivalReason.of(received, file.sha256))) {
            is Result.Err -> begun
            is Result.Ok -> finish(begun.value)
        }
    }

    private suspend fun finish(arrival: ExtraArrival): Result<ExtraArrival?, DomainError> =
        when (val moved = inbox.quarantineExtra(arrival)) {
            is Result.Err -> {
                moved
            }

            is Result.Ok -> {
                when (val done = files.finishExtra(arrival.id)) {
                    is Result.Err -> done
                    is Result.Ok -> ok(arrival)
                }
            }
        }
}
