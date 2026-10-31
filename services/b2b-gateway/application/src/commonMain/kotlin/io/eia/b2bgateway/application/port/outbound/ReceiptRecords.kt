package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 受け取りの記録と監査(ADR-0040 §5・ADR-0041 §11)。1 つの文書について、受け取りの記録・受け付けた注文・送る応答・監査の記録
 * (`b2b.document.receive`)を、1 つのトランザクションで書く。送り直し([Receipt.duplicate] が Resend)なら、前の記録の応答を送り直す。
 */
public interface ReceiptRecords {
    /** 記録して、受け取りの記録の ID を返す。 */
    public suspend fun record(document: DocumentRecord): Result<String, DomainError>
}

/**
 * 1 つの文書の記録。
 *
 * @property refusal 復号の段階で拒否した理由(読めない文書。[opened] と [receipt] は null)
 * @property acknowledgements 送る応答(送り直しのときは空。前の記録の応答を送り直す)
 */
public class DocumentRecord(
    public val file: ReceivedFile,
    public val refusal: String? = null,
    public val opened: OpenedInterchange? = null,
    public val receipt: Receipt? = null,
    public val acknowledgements: List<StoredAcknowledgement> = emptyList(),
)

/** 受け取ったファイル。[fileName] は取引先が付けた名前、[original] は保管した原本(PGP の暗号文)。 */
public data class ReceivedFile(
    val partnerId: String,
    val fileName: String,
    val receivedAt: Instant,
    val original: StoredOriginal,
)

/** 復号した交換。[sha256] はその SHA-256、[signer] は署名した鍵のフィンガープリント、[readableRef] は保管用の鍵で暗号化した写しの場所。 */
public data class OpenedInterchange(
    val sha256: String,
    val signer: String,
    val readableRef: String,
)
