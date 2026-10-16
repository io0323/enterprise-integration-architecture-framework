package io.eia.fileexchange.application.usecase

import io.eia.fileexchange.application.port.inbound.ReceiveFileUseCase
import io.eia.fileexchange.application.port.inbound.ReceiveOutcome
import io.eia.fileexchange.application.port.outbound.Inbox
import io.eia.fileexchange.application.port.outbound.Inspection
import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok

public class ReceiveFileService(
    private val inbox: Inbox,
    private val files: ReceivedFiles,
) : ReceiveFileUseCase {
    @Suppress("ReturnCount") // 記録・検査・移動の失敗のそれぞれで返す
    override suspend fun invoke(name: String): Result<ReceiveOutcome, DomainError> {
        val existing =
            when (val found = files.find(name)) {
                is Result.Err -> return found
                is Result.Ok -> found.value
            }
        if (existing?.state == ReceiptState.STORED) return ok(ReceiveOutcome(existing, resumed = false, alreadyStored = true))
        val record =
            existing ?: when (val decided = decide(name)) {
                is Result.Err -> return decided
                is Result.Ok -> decided.value
            }
        val moved =
            when (record.destination) {
                ReceiptDestination.ARCHIVE -> inbox.archive(name, checkNotNull(record.dataFile))
                ReceiptDestination.QUARANTINE -> inbox.quarantine(name, checkNotNull(record.reason), record.detail.orEmpty())
            }
        if (moved is Result.Err) return moved
        return when (val done = files.stored(name)) {
            is Result.Err -> done
            is Result.Ok -> ok(ReceiveOutcome(record.copy(state = ReceiptState.STORED), resumed = existing != null, alreadyStored = false))
        }
    }

    /** 検査して、移動の決定を「移動中」として記録する(移す前に記録する。ADR-0032 §4)。 */
    private suspend fun decide(name: String): Result<ReceivedFile, DomainError> =
        when (val inspected = inbox.inspect(name)) {
            is Result.Err -> {
                inspected
            }

            is Result.Ok -> {
                val decision =
                    when (val inspection = inspected.value) {
                        is Inspection.Accepted -> {
                            ReceivedFile(
                                name,
                                inspection.sha256,
                                ReceiptDestination.ARCHIVE,
                                inspection.dataFile,
                                null,
                                null,
                                ReceiptState.MOVING,
                            )
                        }

                        is Inspection.Rejected -> {
                            ReceivedFile(
                                name,
                                inspection.sha256,
                                ReceiptDestination.QUARANTINE,
                                null,
                                inspection.reason,
                                inspection.detail,
                                ReceiptState.MOVING,
                            )
                        }
                    }
                files.begin(decision)
            }
        }
}
