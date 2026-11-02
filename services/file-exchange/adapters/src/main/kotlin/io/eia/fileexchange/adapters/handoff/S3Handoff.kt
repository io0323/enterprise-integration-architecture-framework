package io.eia.fileexchange.adapters.handoff

import io.eia.fileexchange.application.port.outbound.Handoff
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.ObjectMissing
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 取引先に送るファイルの受け渡しの場所(同じバケットの `outbound/sales-daily/`。ADR-0032 §8・ADR-0040 §7)。
 * archive のデータファイルを写してから、マニフェストを最後に写す(b2b-gateway はマニフェストのあるファイルだけを送る。受け口と同じ完了の合図)。
 * コピーは上書きなので、何度写しても同じ結果になる。受け渡しの場所のファイルは、b2b-gateway が送り終えて保存期間を過ぎたら消す。
 */
public class S3Handoff(
    private val store: ObjectStore,
    private val archivePrefix: String,
    private val outboundPrefix: String,
) : Handoff {
    override suspend fun handOff(
        name: String,
        dataFile: String,
    ): Result<Unit, DomainError> =
        withContext(Dispatchers.IO) {
            copy(dataFile).flatMap { copy(name + FileManifest.SUFFIX) }
        }

    private fun copy(file: String): Result<Unit, DomainError> =
        store
            .copy(archivePrefix + file, outboundPrefix + file)
            .mapError(FileTransferError::asDomainError)
            .flatMap { copied -> if (copied) ok(Unit) else err(ObjectMissing(archivePrefix + file)) }
}
