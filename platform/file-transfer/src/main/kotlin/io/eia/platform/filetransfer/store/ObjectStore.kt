package io.eia.platform.filetransfer.store

import io.eia.platform.filetransfer.FileTransferError
import io.eia.shared.kernel.Result
import java.nio.file.Path
import java.time.Instant

/** 置き場のオブジェクト。 */
public data class StoredObject(
    val key: String,
    val size: Long,
)

/** 読むだけの置き場(ファイルの到着の監視。ADR-0030 §4)。読み取りの権限だけを持つ identity で使う。 */
public interface ObjectReader {
    /** [prefix] の下のオブジェクト(キーの順)。 */
    public fun list(prefix: String): Result<List<StoredObject>, FileTransferError>

    /** [key] の内容を最大 [maxBytes] + 1 バイトまで読む(超えたことが分かるように)。なければ null。 */
    public fun read(
        key: String,
        maxBytes: Int,
    ): Result<ByteArray?, FileTransferError>
}

/**
 * 読み書きする置き場(本番は [S3ObjectStore])。S3 には名前の変更がないので、移動はコピーと削除で行う(ADR-0032 §4)。
 * どの操作も、同じ引数で繰り返して害がない(削除は、ないキーでも成功)。
 */
public interface ObjectStore : ObjectReader {
    /** [key] の大きさ。なければ null。 */
    public fun head(key: String): Result<StoredObject?, FileTransferError>

    /** [key] を [target] に書き出す(上書き)。なければ false(ファイルは作らない)。 */
    public fun download(
        key: String,
        target: Path,
    ): Result<Boolean, FileTransferError>

    /** [source] の内容を [key] に置く(1 回の PUT。置き終わるまで、ほかからは見えない)。 */
    public fun upload(
        key: String,
        source: Path,
    ): Result<Unit, FileTransferError>

    /** [bytes] を [key] に置く(マニフェストなど小さいもの)。 */
    public fun put(
        key: String,
        bytes: ByteArray,
    ): Result<Unit, FileTransferError>

    /** [sourceKey] を [targetKey] にコピーする(上書き)。コピー元がなければ false。 */
    public fun copy(
        sourceKey: String,
        targetKey: String,
    ): Result<Boolean, FileTransferError>

    /** [key] を消す。なくても成功。 */
    public fun delete(key: String): Result<Unit, FileTransferError>
}

/**
 * 消せない置き場(原本の保管。ADR-0040 §5)。S3 の Object Lock(COMPLIANCE)で置き、保持期限までは誰も消せず、期限を縮められない。
 * バケットはバージョニングと Object Lock を有効にしてあること。同じキーに置き直すと、上書きではなく新しい版になる。
 */
public interface LockedObjectWriter {
    /** [bytes] を [key] に、保持期限 [retainUntil] の COMPLIANCE で置く。置いた版の ID を返す。 */
    public fun putLocked(
        key: String,
        bytes: ByteArray,
        retainUntil: Instant,
    ): Result<String, FileTransferError>
}

/** 版を指定して読む置き場(Object Lock で置いた原本を、置いたときの版のまま読む。ADR-0040 §7)。 */
public interface VersionedObjectReader {
    /** [key] の版 [versionId] を最大 [maxBytes] + 1 バイトまで読む(超えたことが分かるように)。なければ null。 */
    public fun readVersion(
        key: String,
        versionId: String,
        maxBytes: Int,
    ): Result<ByteArray?, FileTransferError>
}
