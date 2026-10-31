package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.AcceptDocumentUseCase
import io.eia.b2bgateway.application.port.inbound.AcceptedDocument
import io.eia.b2bgateway.application.port.inbound.DocumentOutcome
import io.eia.b2bgateway.application.port.inbound.IncomingDocument
import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.b2bgateway.application.port.inbound.ReceiveInterchangeUseCase
import io.eia.b2bgateway.application.port.inbound.ReceivedDocument
import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.DocumentCrypto
import io.eia.b2bgateway.application.port.outbound.DocumentRecord
import io.eia.b2bgateway.application.port.outbound.OpenedInterchange
import io.eia.b2bgateway.application.port.outbound.Opening
import io.eia.b2bgateway.application.port.outbound.OriginalArchive
import io.eia.b2bgateway.application.port.outbound.ReceiptRecords
import io.eia.b2bgateway.application.port.outbound.ReceivedFile
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.map
import kotlin.time.Clock

/** [AcceptDocumentUseCase] の実装。状態を持たない(原本・記録・監査は Port の先)。 */
public class AcceptDocumentService(
    private val receive: ReceiveInterchangeUseCase,
    private val archive: OriginalArchive,
    private val crypto: DocumentCrypto,
    private val records: ReceiptRecords,
    private val clock: Clock,
) : AcceptDocumentUseCase {
    @Suppress("ReturnCount") // 読めない文書と、Port の失敗のそれぞれで返す
    override suspend fun invoke(document: IncomingDocument): Result<AcceptedDocument, DomainError> {
        val receivedAt = clock.now()
        val partnerId = document.partnerId
        val original = archive.storeReceived(partnerId, document.content).getOrElse { return err(it) }
        val file = ReceivedFile(partnerId, document.fileName, receivedAt, original)
        val opened =
            when (val opening = crypto.open(partnerId, document.content).getOrElse { return err(it) }) {
                is Opening.Refused -> {
                    val record = DocumentRecord(file, refusal = opening.reason)
                    return records.record(record).map { AcceptedDocument(it, DocumentOutcome.UNREADABLE, null) }
                }

                is Opening.Opened -> {
                    opening
                }
            }
        val readable = archive.storeReadable(partnerId, opened.content, opened.sha256).getOrElse { return err(it) }
        val receipt = receive(ReceivedDocument(partnerId, opened.content, opened.sha256)).getOrElse { return err(it) }
        val acknowledgements =
            listOfNotNull(
                receipt.contrl?.let { AcknowledgementKind.CONTRL to it },
                receipt.aperak?.let { AcknowledgementKind.APERAK to it },
            ).map { (kind, plain) -> seal(partnerId, kind, plain).getOrElse { return err(it) } }
        val record =
            DocumentRecord(
                file = file,
                opened = OpenedInterchange(opened.sha256, opened.signer, readable),
                receipt = receipt,
                acknowledgements = acknowledgements,
            )
        return records.record(record).map { AcceptedDocument(it, outcome(receipt), receipt) }
    }

    /** 応答を取引先あてに暗号化・署名し、原本として保管する。 */
    private suspend fun seal(
        partnerId: String,
        kind: AcknowledgementKind,
        plain: ByteArray,
    ): Result<StoredAcknowledgement, DomainError> {
        val sealed = crypto.seal(partnerId, plain).getOrElse { return err(it) }
        return archive.storeSent(partnerId, kind, sealed, plain)
    }

    private fun outcome(receipt: Receipt): DocumentOutcome =
        when {
            receipt.duplicate is DuplicateCheck.Resend -> DocumentOutcome.RESENT
            receipt.result is InterchangeResult.Unreadable -> DocumentOutcome.NO_ENVELOPE
            receipt.result is InterchangeResult.Rejected -> DocumentOutcome.REJECTED
            else -> DocumentOutcome.CHECKED
        }
}
