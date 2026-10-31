package io.eia.b2bgateway.adapters.archive

import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.OriginalArchive
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.application.port.outbound.StoredOriginal
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeyNames
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.store.LockedObjectWriter
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * 原本の保管(ADR-0040 §5・§8)。S3 互換ストレージの Object Lock(COMPLIANCE)に、保持期間 [retention] で置く。キーは次の形。
 * | 置くもの | キー |
 * |---|---|
 * | 受け取った原本(PGP の暗号文) | `originals/{取引先}/received/{SHA-256}.pgp` |
 * | 送った応答の原本(取引先あての暗号文) | `originals/{取引先}/sent/{contrl・aperak}/{SHA-256}.pgp` |
 * | 復号した交換・応答(保管用の鍵で暗号化) | `readable/{取引先}/received/{交換の SHA-256}.edi.pgp`・`readable/{取引先}/sent/{種類}/{SHA-256}.edi.pgp` |
 *
 * - 原本は手を加えずにバイト列のまま置く。同じキーに置き直すと新しい版になる(やり直しで版が増えるだけで、害はない)。
 * - 復号した交換は、**自社の保管用の鍵**([ArchiveKeyNames.archivePublicKey]。サービスが持つのは公開鍵だけ)で暗号化して置く。
 *   取引先とやり取りする鍵(定期的に差し替える)と保管の鍵を分け、保持期間の間に読むための秘密鍵は担当部門がサービスの外で持つ(ADR-0040 §8)。
 *   自社の署名の鍵で署名も付け、保管したのが b2b-gateway であることを示す。
 * - 参照([StoredOriginal.ref])は `{キー}#{版の ID}`。
 */
public class S3OriginalArchive(
    private val store: LockedObjectWriter,
    private val secrets: SecretProvider,
    private val keys: ArchiveKeyNames,
    private val retention: Duration,
    private val workDir: Path,
    private val clock: Clock = Clock.systemUTC(),
) : OriginalArchive {
    init {
        require(!retention.isNegative && !retention.isZero) { "原本の保持期間は正の値にしてください" }
    }

    override suspend fun storeReceived(
        partnerId: String,
        content: ByteArray,
    ): Result<StoredOriginal, DomainError> {
        val sha = sha256(content)
        return put("originals/$partnerId/received/$sha.pgp", content).map { StoredOriginal(it, sha) }
    }

    override suspend fun storeReadable(
        partnerId: String,
        interchange: ByteArray,
        sha256: String,
    ): Result<String, DomainError> = readable("readable/$partnerId/received/$sha256.edi.pgp", interchange)

    override suspend fun storeSent(
        partnerId: String,
        kind: AcknowledgementKind,
        sealed: ByteArray,
        plain: ByteArray,
    ): Result<StoredAcknowledgement, DomainError> {
        val sha = sha256(sealed)
        val folder = kind.name.lowercase()
        return put("originals/$partnerId/sent/$folder/$sha.pgp", sealed).flatMap { original ->
            readable("readable/$partnerId/sent/$folder/${sha256(plain)}.edi.pgp", plain).map { readable ->
                StoredAcknowledgement(kind, StoredOriginal(original, sha), readable)
            }
        }
    }

    /** 保管用の鍵で暗号化(と自社の鍵で署名)して置く。 */
    private suspend fun readable(
        key: String,
        plain: ByteArray,
    ): Result<String, DomainError> =
        withContext(Dispatchers.IO) {
            PgpKeys
                .load(secrets, PgpKeyNames(keys.archivePublicKey, keys.ownSecretKey, keys.ownPassphrase))
                .flatMap { archive ->
                    withTempFiles(workDir, plain) { source, target ->
                        PgpFiles
                            .encryptAndSign(
                                source,
                                target,
                                archive,
                                key.substringAfterLast('/').removeSuffix(".pgp"),
                            ).map { Files.readAllBytes(target) }
                    }
                }.mapError(FileTransferError::asDomainError)
        }.flatMap { put(key, it) }

    private suspend fun put(
        key: String,
        bytes: ByteArray,
    ): Result<String, DomainError> =
        withContext(Dispatchers.IO) {
            when (val put = store.putLocked(key, bytes, clock.instant().plus(retention))) {
                is Result.Ok -> ok("$key#${put.value}")
                is Result.Err -> err(put.error.asDomainError())
            }
        }
}

/**
 * 保管用の暗号化に使う鍵の秘密情報の名前。[archivePublicKey] は自社の保管用の鍵の公開鍵(秘密鍵はサービスに渡さない)、
 * [ownSecretKey]・[ownPassphrase] は自社の署名の鍵(取引先との文書の署名と同じ鍵)。
 */
public data class ArchiveKeyNames(
    val archivePublicKey: SecretName,
    val ownSecretKey: SecretName,
    val ownPassphrase: SecretName,
)
