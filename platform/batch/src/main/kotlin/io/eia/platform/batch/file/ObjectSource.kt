package io.eia.platform.batch.file

import io.eia.platform.batch.BatchError
import io.eia.shared.kernel.Result

/** 置き場のオブジェクト。 */
public data class StoredObject(
    val key: String,
    val size: Long,
)

/** ファイルの到着を見る置き場(本番は [S3ObjectSource])。 */
public interface ObjectSource {
    /** [prefix] の下のオブジェクト(キーの順)。 */
    public fun list(prefix: String): Result<List<StoredObject>, BatchError>

    /** [key] の内容を最大 [maxBytes] + 1 バイトまで読む(超えたことが分かるように)。なければ null。 */
    public fun read(
        key: String,
        maxBytes: Int,
    ): Result<ByteArray?, BatchError>
}
