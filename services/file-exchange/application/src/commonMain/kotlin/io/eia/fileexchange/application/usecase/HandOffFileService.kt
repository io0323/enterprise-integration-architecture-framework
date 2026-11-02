package io.eia.fileexchange.application.usecase

import io.eia.fileexchange.application.port.inbound.HandOffFileUseCase
import io.eia.fileexchange.application.port.inbound.HandOffUnsentUseCase
import io.eia.fileexchange.application.port.inbound.HandoffReport
import io.eia.fileexchange.application.port.inbound.UnsentReport
import io.eia.fileexchange.application.port.outbound.FileLocks
import io.eia.fileexchange.application.port.outbound.Handoff
import io.eia.fileexchange.application.port.outbound.PastDeliveries
import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.time.Instant

/** [HandOffFileUseCase] の実装。 */
public class HandOffFileService(
    private val files: ReceivedFiles,
    private val handoff: Handoff,
) : HandOffFileUseCase {
    @Suppress("ReturnCount") // 記録の読み込みの失敗と、受け取りが終わっていないとき・quarantine のときに返す
    override suspend fun invoke(name: String): Result<HandoffReport, DomainError> {
        val file = files.find(name).getOrElse { return err(it) }
        if (file == null || file.state != ReceiptState.STORED) return err(ConflictError("$name の受け取りが終わっていません"))
        if (file.destination == ReceiptDestination.QUARANTINE) return ok(HandoffReport(name, quarantined = true))
        return handoff.handOff(name, checkNotNull(file.dataFile)).map { HandoffReport(name, quarantined = false) }
    }
}

/** [HandOffUnsentUseCase] の実装。1 つずつ、受け取りの実行と同じロックの中で写す(同じファイルを同時に扱わない)。 */
public class HandOffUnsentService(
    private val past: PastDeliveries,
    private val handOffFile: HandOffFileUseCase,
    private val locks: FileLocks,
) : HandOffUnsentUseCase {
    @Suppress("ReturnCount") // 一覧の失敗・dry-run・写した結果のそれぞれで返す
    override suspend fun invoke(
        since: Instant,
        execute: Boolean,
    ): Result<UnsentReport, DomainError> {
        val candidates = past.unsent(since).getOrElse { return err(it) }
        if (!execute) return ok(UnsentReport(candidates, emptyList(), emptyList()))
        val handedOff = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, DomainError>>()
        candidates.forEach { name ->
            when (val result = locks.withLock(name) { handOffFile(name) }) {
                is Result.Ok -> if (result.value != null) handedOff += name else failed += name to ConflictError("$name は受け取りの実行の中です")
                is Result.Err -> failed += name to result.error
            }
        }
        return ok(UnsentReport(candidates, handedOff, failed))
    }
}
