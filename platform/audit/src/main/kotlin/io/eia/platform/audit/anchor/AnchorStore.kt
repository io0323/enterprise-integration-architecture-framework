package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.eia.shared.kernel.Result
import java.time.Instant

/**
 * アンカーの保存先(Port)。実装は S3 互換ストレージの Object Lock(COMPLIANCE)を使う [S3AnchorStore]。
 *
 * 同じキーに何度保存しても上書きせず、版として残す(バケットのバージョニング)。
 */
public interface AnchorStore {
    /** [body] を COMPLIANCE モード・保持期限 [retainUntil] つきで保存し、版の ID を返す。 */
    public fun put(
        key: String,
        body: ByteArray,
        retainUntil: Instant,
    ): Result<String, AuditError>

    /** [prefix] の下のすべての版(削除マーカーを含む)を、内容と保持の設定とともに返す。 */
    public fun listVersions(prefix: String): Result<List<AnchorVersion>, AuditError>
}

/**
 * 保存されたアンカーの 1 版。
 *
 * @property lastModified ストレージが記録した保存の時刻(アプリの時計ではなく、ストレージの時計)
 * @property body 内容。削除マーカーと、読めなかった版は null([readError] に理由)
 * @property retentionMode 保持モード(`COMPLIANCE` / `GOVERNANCE`)。保持の設定がなければ null
 */
public data class AnchorVersion(
    val key: String,
    val versionId: String,
    val lastModified: Instant,
    val isDeleteMarker: Boolean,
    val body: ByteArray?,
    val retentionMode: String?,
    val retainUntil: Instant?,
    val readError: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is AnchorVersion &&
            key == other.key &&
            versionId == other.versionId &&
            lastModified == other.lastModified &&
            isDeleteMarker == other.isDeleteMarker &&
            (body contentEquals other.body) &&
            retentionMode == other.retentionMode &&
            retainUntil == other.retainUntil &&
            readError == other.readError

    override fun hashCode(): Int = key.hashCode() * PRIME + versionId.hashCode()

    private companion object {
        const val PRIME = 31
    }
}
