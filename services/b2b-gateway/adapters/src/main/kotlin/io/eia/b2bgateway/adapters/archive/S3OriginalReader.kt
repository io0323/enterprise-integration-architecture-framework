package io.eia.b2bgateway.adapters.archive

import io.eia.b2bgateway.application.port.outbound.OriginalReader
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.store.VersionedObjectReader
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

/** 保管した原本を、参照(`{キー}#{版の ID}`。[S3OriginalArchive])の版のまま読む。大きさの上限は [maxBytes]。 */
public class S3OriginalReader(
    private val store: VersionedObjectReader,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : OriginalReader {
    override suspend fun read(ref: String): Result<ByteArray, DomainError> {
        val key = ref.substringBefore('#')
        val version = ref.substringAfter('#', missingDelimiterValue = "")
        if (key.isEmpty() || version.isEmpty()) return err(UnexpectedError("原本の参照の形が違います"))
        return withContext(Dispatchers.IO) {
            store
                .readVersion(key, version, maxBytes)
                .mapError(FileTransferError::asDomainError)
                .flatMap { bytes -> if (bytes == null) err(NotFoundError("original", ref)) else ok(bytes) }
        }
    }

    public companion object {
        /** 応答の原本の大きさの上限(応答は小さい)。 */
        public const val DEFAULT_MAX_BYTES: Int = 16 * 1024 * 1024
    }
}
