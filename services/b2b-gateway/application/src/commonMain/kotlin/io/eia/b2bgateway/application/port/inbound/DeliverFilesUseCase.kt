package io.eia.b2bgateway.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 受け渡しの場所(`outbound/`)のファイルを、台帳で受け取る取引先に送る(ADR-0040 §7。P12 ⑦)。
 * - 受け渡しの場所を **毎回すべて一覧し**、ファイルと取引先の組ごとに送信の記録がないものだけを送る(名前の順の位置では見ない。過去の日付で
 *   作り直したファイルは名前の順で前に来るため)
 * - 送る前に、データファイルの SHA-256 をマニフェストと照らし合わせる(違えば送らない)
 * - 送るのは取引先の送信の仕組み([PartnerOutbox][io.eia.b2bgateway.application.port.outbound.PartnerOutbox])。取引先あてに暗号化して署名し、
 *   取引先の側の正式名は `{データファイル}.pgp`(送る前に取引先の側の正式名を確かめる)
 * - 送り終えた(または受け取る取引先のない)ファイルは、マニフェストの作成の時刻から保存期間を過ぎたら、受け渡しの場所から消す
 *   (一覧が増え続けないように。原本は file-exchange の archive にある)。送れていないファイルは消さない
 *
 * 1 つのファイル・1 つの取引先の失敗は、ほかを止めない。
 */
public interface DeliverFilesUseCase {
    public suspend operator fun invoke(): Result<FileDeliverySummary, DomainError>
}

/** 送信の結果。[sent] は送った(または取引先の側にすでにあった)ファイルと取引先の組、[deleted] は消したファイル。 */
public class FileDeliverySummary(
    public val sent: List<Pair<String, String>>,
    public val failed: List<FailedFileDelivery>,
    public val deleted: List<String>,
    public val invalid: List<String>,
)

/** 送れなかったファイル。[partnerId] はファイルを読めない・照合に失敗したときは null(どの取引先にも送っていない)。 */
public data class FailedFileDelivery(
    val name: String,
    val partnerId: String?,
    val error: DomainError,
)

/** 受け渡しの場所のデータファイルの SHA-256 が、マニフェストと一致しない(写しの誤り・改竄。送らずに運用に知らせる)。 */
public data class HandoffMismatch(
    val name: String,
) : DomainError.NonRetryable {
    override val code: String get() = "handoff_mismatch"
    override val message: String get() = "受け渡しの場所のファイル $name の SHA-256 がマニフェストと一致しません"
}
