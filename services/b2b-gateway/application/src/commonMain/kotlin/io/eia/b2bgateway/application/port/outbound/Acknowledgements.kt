package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 取引先に送る応答の記録(受け付けのときに送信待ちで記録したもの。ADR-0041 §5)。 */
public interface Acknowledgements {
    /** 取引先の送信待ちの応答(古い順に [limit] 件まで)。 */
    public suspend fun pending(
        partnerId: String,
        limit: Int,
    ): Result<List<PendingAcknowledgement>, DomainError>

    /** 送った印を付け、監査(`b2b.ack.send`)を記録する(1 つのトランザクション)。 */
    public suspend fun sent(
        acknowledgement: PendingAcknowledgement,
        remoteName: String,
        alreadyPresent: Boolean,
    ): Result<Unit, DomainError>
}

/** 送信待ちの応答。[original] は送る暗号文の原本(場所と SHA-256)。 */
public data class PendingAcknowledgement(
    val id: String,
    val documentId: String,
    val partnerId: String,
    val kind: AcknowledgementKind,
    val original: StoredOriginal,
)
