package io.eia.b2bgateway.adapters.handoff

import io.eia.b2bgateway.application.port.outbound.HandoffFile
import io.eia.b2bgateway.application.port.outbound.HandoffFiles
import io.eia.b2bgateway.application.port.outbound.HandoffListing
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 取引先に送るファイルの受け渡しの場所(S3 互換ストレージ。既定はバケット `eiaf-file-exchange` の `outbound/sales-daily/`。ADR-0040 §7)。
 * file-exchange が合格のファイルのデータファイルを写し、マニフェスト(`{名前}.manifest.json`。contracts/files の manifest)を最後に置く。
 * マニフェストのあるファイルだけを一覧に出す(データファイルだけのものは、写している途中)。
 *
 * 受け渡しの identity(`eiaf-b2b-handoff`)は、受け渡しの場所を読む・消すだけ(archive・受け口は読めない)。
 */
public class S3HandoffFiles(
    private val store: ObjectStore,
    private val prefix: String,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : HandoffFiles {
    init {
        require(prefix.endsWith("/")) { "受け渡しの場所の接頭辞は / で終えてください" }
    }

    override suspend fun list(): Result<HandoffListing, DomainError> =
        io {
            store.list(prefix).mapError(FileTransferError::asDomainError).flatMap { objects ->
                val files = mutableListOf<HandoffFile>()
                val invalid = mutableListOf<String>()
                objects.map { it.key }.filter { it.endsWith(FileManifest.SUFFIX) }.forEach { key ->
                    val name = key.removePrefix(prefix).removeSuffix(FileManifest.SUFFIX)
                    when (val manifest = manifest(key, name)) {
                        is Result.Ok -> manifest.value?.let { files += it } ?: run { invalid += name }
                        is Result.Err -> return@flatMap manifest
                    }
                }
                ok(HandoffListing(files, invalid))
            }
        }

    override suspend fun read(file: HandoffFile): Result<ByteArray, DomainError> =
        io {
            val key = prefix + file.dataFile
            store.read(key, maxBytes).mapError(FileTransferError::asDomainError).flatMap { bytes ->
                when {
                    bytes == null -> err(NotFoundError("handoff file", file.dataFile))
                    bytes.size > maxBytes -> err(UnexpectedError("受け渡しの場所のファイル ${file.name} が大きすぎます(${maxBytes / MIB} MiB まで)"))
                    else -> ok(bytes)
                }
            }
        }

    /** マニフェストを先に消す(途中で落ちても、データファイルだけが残り、一覧には出ない)。 */
    override suspend fun delete(file: HandoffFile): Result<Unit, DomainError> =
        io {
            store
                .delete(prefix + file.name + FileManifest.SUFFIX)
                .flatMap { store.delete(prefix + file.dataFile) }
                .mapError(FileTransferError::asDomainError)
        }

    /** マニフェストを読む。形が違う・データファイルの名前がマニフェストの名前と合わなければ null(一覧の invalid)。 */
    private fun manifest(
        key: String,
        name: String,
    ): Result<HandoffFile?, DomainError> =
        store.read(key, FileManifest.MAX_BYTES).mapError(FileTransferError::asDomainError).flatMap { bytes ->
            val manifest = bytes?.let { (FileManifest.parse(it) as? Result.Ok)?.value }
            ok(
                manifest?.takeIf { it.file.startsWith("$name.") }?.let {
                    HandoffFile(name, it.file, it.sha256, it.createdAt)
                },
            )
        }

    private suspend fun <T> io(block: () -> Result<T, DomainError>): Result<T, DomainError> = withContext(Dispatchers.IO) { block() }

    public companion object {
        private const val MIB = 1024 * 1024

        /** 1 つのファイルの大きさの上限(暗号化のためにメモリに読む。日次の売上ファイルは小さい)。 */
        public const val DEFAULT_MAX_BYTES: Int = 64 * MIB
    }
}
