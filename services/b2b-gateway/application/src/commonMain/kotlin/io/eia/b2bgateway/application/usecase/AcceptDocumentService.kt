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
import io.eia.b2bgateway.application.port.outbound.OrderSubmission
import io.eia.b2bgateway.application.port.outbound.OriginalArchive
import io.eia.b2bgateway.application.port.outbound.ReceiptRecords
import io.eia.b2bgateway.application.port.outbound.ReceivedFile
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.domain.order.OrderRequests
import io.eia.b2bgateway.domain.order.SubmissionKey
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.map
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** [AcceptDocumentUseCase] の実装。状態を持たない(原本・記録・監査は Port の先)。 */
public class AcceptDocumentService(
    private val receive: ReceiveInterchangeUseCase,
    private val archive: OriginalArchive,
    private val crypto: DocumentCrypto,
    private val records: ReceiptRecords,
    private val clock: Clock,
    private val submitWindow: Duration = DEFAULT_SUBMIT_WINDOW,
) : AcceptDocumentUseCase {
    init {
        require(submitWindow.isPositive() && submitWindow < IDEMPOTENCY_RETENTION) {
            "送り直しの上限は、order の Idempotency-Key の保存期間(24 時間)より短くしてください"
        }
    }

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
                submissions = submissions(receipt, receivedAt),
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

    /**
     * 業務の段階を通った注文の送信待ち。要求の本文は、受け付けの時点の対応表で作って固定する(ADR-0040 §6)。
     * 試験の交換は社内に取り込まない(ADR-0041 §6)。送り直しの交換には、業務の段階の結果がない(前の記録で取り込んでいる)。
     */
    @Suppress("ReturnCount") // 交換の枠を受け付けていない・試験の交換は、送信待ちを作らずに返す
    private fun submissions(
        receipt: Receipt,
        receivedAt: Instant,
    ): List<OrderSubmission> {
        val header = (receipt.result as? InterchangeResult.Checked)?.header ?: return emptyList()
        if (header.test) return emptyList()
        val partner = receipt.partner
        return receipt.orders.filter { it.accepted }.map { decision ->
            val key = SubmissionKey(partner.partnerId, header.controlReference, decision.messageReference)
            OrderSubmission(
                orderNumber = decision.order.orderNumber,
                messageReference = decision.messageReference,
                request = OrderRequests.from(key, decision.order, decision.resolution),
                header = header,
                positiveAperak = partner.positiveAperak,
                aperakDeadline = receivedAt + partner.aperakDeadline,
                retryUntil = receivedAt + submitWindow,
            )
        }
    }

    private fun outcome(receipt: Receipt): DocumentOutcome =
        when {
            receipt.duplicate is DuplicateCheck.Resend -> DocumentOutcome.RESENT
            receipt.result is InterchangeResult.Unreadable -> DocumentOutcome.NO_ENVELOPE
            receipt.result is InterchangeResult.Rejected -> DocumentOutcome.REJECTED
            else -> DocumentOutcome.CHECKED
        }

    private companion object {
        // order の Idempotency-Key の保存期間(Framework 5.4・ADR-0022 §3)と、送り直しの上限の既定(ADR-0040 §6)
        val IDEMPOTENCY_RETENTION = 24.hours
        val DEFAULT_SUBMIT_WINDOW = 12.hours
    }
}
