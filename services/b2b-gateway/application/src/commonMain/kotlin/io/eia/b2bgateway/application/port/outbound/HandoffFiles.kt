package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 取引先に送るファイルの受け渡しの場所(S3 互換ストレージの `outbound/sales-daily/`。file-exchange が合格のファイルを写し、マニフェストを最後に
 * 置く。ADR-0040 §7)。b2b-gateway は読む・送り終えた古いファイルを消すだけ。
 */
public interface HandoffFiles {
    /** 受け渡しの場所にある、マニフェストのそろったファイルのすべて(名前の順)。形の違うマニフェストは [HandoffListing.invalid]。 */
    public suspend fun list(): Result<HandoffListing, DomainError>

    /** データファイルを読む。 */
    public suspend fun read(file: HandoffFile): Result<ByteArray, DomainError>

    /** データファイルとマニフェストを消す(保存期間を過ぎ、送り終えたもの)。 */
    public suspend fun delete(file: HandoffFile): Result<Unit, DomainError>
}

/** 受け渡しの場所のファイル。[name] は拡張子を除いた名前、[sha256] と [createdAt] はマニフェストの値。 */
public data class HandoffFile(
    val name: String,
    val dataFile: String,
    val sha256: String,
    val createdAt: Instant,
)

public class HandoffListing(
    public val files: List<HandoffFile>,
    public val invalid: List<String>,
)

/** 取引先の台帳で、データセット(`{system}_{dataset}`)のファイルを受け取る取引先。 */
public fun interface FileRecipients {
    public fun of(dataset: String): List<String>
}

/** 取引先へのファイルの送信の記録(ファイルと取引先の組ごとに 1 つ)。記録と監査(`b2b.file.send`)は 1 つのトランザクションで書く。 */
public interface FileDeliveries {
    /** [name] のファイルを送り終えた取引先。 */
    public suspend fun delivered(name: String): Result<Set<String>, DomainError>

    public suspend fun record(delivery: FileDelivery): Result<Unit, DomainError>
}

/** 送り終えたファイル。[sha256] は送ったデータファイル(平文)の SHA-256、[alreadyPresent] は取引先の側にすでにあった。 */
public data class FileDelivery(
    val name: String,
    val partnerId: String,
    val remoteName: String,
    val sha256: String,
    val alreadyPresent: Boolean,
)
