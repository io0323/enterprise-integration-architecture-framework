package io.eia.fileexchange.adapters.file

import io.eia.fileexchange.application.port.outbound.Inbox
import io.eia.fileexchange.application.port.outbound.Inspection
import io.eia.fileexchange.application.port.outbound.WaitingFile
import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.move.FileMover
import io.eia.platform.filetransfer.move.QuarantineReason
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.filetransfer.verify.RejectionReason
import io.eia.platform.filetransfer.verify.Verification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * 受け口(S3 互換ストレージ)。platform/file-transfer の検査([FileVerifier])と移動([FileMover])を使う(ADR-0032 §3・§4)。
 * 置き場は受け取りの identity(読み取り・一覧・移動)で使う。受け取り済みの名前で届いたファイルは
 * `{quarantine}extra/{番号}/` に移し、前に届いたもの(quarantine の同じ名前)を上書きしない。
 */
public class S3Inbox(
    private val store: ObjectStore,
    private val verifier: FileVerifier,
    private val prefixes: InboxPrefixes,
    private val clock: Clock = Clock.System,
) : Inbox {
    private val mover = FileMover(store)

    override suspend fun inspect(name: String): Result<Inspection, DomainError> =
        io {
            when (val verified = verifier.verify(prefixes.inbox, manifestKey(name))) {
                is Result.Err -> {
                    err(verified.error.asDomainError())
                }

                is Result.Ok -> {
                    ok(
                        when (val verification = verified.value) {
                            is Verification.Accepted -> {
                                Inspection.Accepted(verification.manifest.sha256, verification.manifest.file)
                            }

                            is Verification.Rejected -> {
                                Inspection.Rejected(verification.reason.code, verification.detail, verification.manifest?.sha256)
                            }
                        },
                    )
                }
            }
        }

    override suspend fun archive(
        name: String,
        dataFile: String,
    ): Result<Unit, DomainError> = io { mover.archive(prefixes.inbox, name, dataFile, prefixes.archive).domain() }

    override suspend fun quarantine(
        name: String,
        reason: String,
        detail: String,
    ): Result<Unit, DomainError> {
        val code = RejectionReason.entries.firstOrNull { it.code == reason } ?: return err(UnexpectedError("知らない理由のコードです($reason)"))
        return io {
            mover
                .quarantine(
                    prefixes.inbox,
                    name,
                    prefixes.quarantine,
                    QuarantineReason(code, detail, clock.now(), null),
                ).domain()
        }
    }

    override suspend fun quarantineExtra(arrival: ExtraArrival): Result<Unit, DomainError> {
        val reason =
            when (arrival.reason) {
                ExtraArrivalReason.DUPLICATE -> {
                    QuarantineReason(RejectionReason.DUPLICATE, "受け取り済みのファイルと同じ名前・同じ SHA-256 です", clock.now(), null)
                }

                ExtraArrivalReason.NAME_REUSED -> {
                    QuarantineReason(RejectionReason.NAME_REUSED, "受け取り済みのファイルと同じ名前で、中身が違います", clock.now(), null)
                }
            }
        return io { mover.quarantine(prefixes.inbox, arrival.name, "${prefixes.quarantine}extra/${arrival.id}/", reason).domain() }
    }

    override suspend fun waiting(): Result<List<WaitingFile>, DomainError> =
        io {
            when (val listed = store.list(prefixes.inbox)) {
                is Result.Err -> {
                    err(listed.error.asDomainError())
                }

                is Result.Ok -> {
                    ok(
                        listed.value
                            .map { it.key.removePrefix(prefixes.inbox) }
                            // 受け口の直下のマニフェストだけ(下の階層は別の受け口)
                            .filter { it.endsWith(FileName.MANIFEST_SUFFIX) && '/' !in it }
                            .map { it.removeSuffix(FileName.MANIFEST_SUFFIX) }
                            .mapNotNull { name -> read(name).getOrNull() },
                    )
                }
            }
        }

    override suspend fun waiting(name: String): Result<WaitingFile?, DomainError> = io { read(name) }

    /** マニフェストを読む。なければ null。読めない(形式の誤り)なら、SHA-256 と時刻のない [WaitingFile]。 */
    private fun read(name: String): Result<WaitingFile?, DomainError> =
        when (val read = store.read(manifestKey(name), FileManifest.MAX_BYTES)) {
            is Result.Err -> {
                err(read.error.asDomainError())
            }

            is Result.Ok -> {
                ok(
                    read.value?.let { bytes ->
                        when (val parsed = FileManifest.parse(bytes)) {
                            is Result.Ok -> WaitingFile(name, parsed.value.sha256, parsed.value.createdAt)
                            is Result.Err -> WaitingFile(name, null, null)
                        }
                    },
                )
            }
        }

    private fun manifestKey(name: String): String = prefixes.inbox + name + FileName.MANIFEST_SUFFIX

    private suspend fun <T> io(block: () -> Result<T, DomainError>): Result<T, DomainError> = withContext(Dispatchers.IO) { block() }

    private fun Result<Unit, io.eia.platform.filetransfer.FileTransferError>.domain(): Result<Unit, DomainError> =
        when (this) {
            is Result.Ok -> this
            is Result.Err -> err(error.asDomainError())
        }
}

/** 受け口・archive・quarantine の接頭辞(どれも `/` で終わる)。 */
public data class InboxPrefixes(
    val inbox: String,
    val archive: String,
    val quarantine: String,
) {
    init {
        listOf(inbox, archive, quarantine).forEach { require(it.endsWith("/")) { "接頭辞は / で終えてください: $it" } }
        require(listOf(inbox, archive, quarantine).distinct().size == listOf(inbox, archive, quarantine).size) {
            "受け口・archive・quarantine は別の接頭辞にしてください"
        }
    }
}
