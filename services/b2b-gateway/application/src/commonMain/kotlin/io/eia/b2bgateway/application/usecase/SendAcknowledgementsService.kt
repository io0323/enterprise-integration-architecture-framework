package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.FailedAcknowledgement
import io.eia.b2bgateway.application.port.inbound.OriginalMismatch
import io.eia.b2bgateway.application.port.inbound.SendAcknowledgementsUseCase
import io.eia.b2bgateway.application.port.inbound.SendSummary
import io.eia.b2bgateway.application.port.outbound.Acknowledgements
import io.eia.b2bgateway.application.port.outbound.Delivery
import io.eia.b2bgateway.application.port.outbound.Destination
import io.eia.b2bgateway.application.port.outbound.OriginalReader
import io.eia.b2bgateway.application.port.outbound.OutboundDocument
import io.eia.b2bgateway.application.port.outbound.PartnerOutbox
import io.eia.b2bgateway.application.port.outbound.PendingAcknowledgement
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.ok

/**
 * [SendAcknowledgementsUseCase] の実装。状態を持たない。取引先の側の正式名は、応答の記録ごとに 1 つ(`{contrl・aperak}-{記録の ID}.edi.pgp`)。
 * 送り直し(同じ交換の再送)では、前の応答と同じ原本を指す新しい記録ができるので、新しい名前で、もう一度届く。
 */
public class SendAcknowledgementsService(
    private val acknowledgements: Acknowledgements,
    private val originals: OriginalReader,
    private val outbox: PartnerOutbox,
    private val sha256: (ByteArray) -> String,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : SendAcknowledgementsUseCase {
    override suspend fun invoke(partnerId: String): Result<SendSummary, DomainError> {
        val pending = acknowledgements.pending(partnerId, batchSize).getOrElse { return err(it) }
        val sent = mutableListOf<String>()
        val failed = mutableListOf<FailedAcknowledgement>()
        pending.forEach { acknowledgement ->
            when (val result = send(acknowledgement)) {
                is Result.Ok -> sent += acknowledgement.id
                is Result.Err -> failed += FailedAcknowledgement(acknowledgement.id, result.error)
            }
        }
        return ok(SendSummary(sent, failed))
    }

    private suspend fun send(acknowledgement: PendingAcknowledgement): Result<Unit, DomainError> {
        val remoteName = "${acknowledgement.kind.name.lowercase()}-${acknowledgement.id}.edi.pgp"
        return originals
            .read(acknowledgement.original.ref)
            .flatMap { content ->
                // 送るのは原本として保管した暗号文そのもの。置き場の誤りで違うものを送らないように、記録の SHA-256 と照らし合わせる
                if (sha256(content) != acknowledgement.original.sha256) {
                    err(OriginalMismatch(acknowledgement.original.ref))
                } else {
                    val document = OutboundDocument(Destination.ACKNOWLEDGEMENTS, remoteName, content, sealed = true)
                    outbox.deliver(acknowledgement.partnerId, document)
                }
            }.flatMap { delivery -> acknowledgements.sent(acknowledgement, remoteName, delivery == Delivery.ALREADY_PRESENT) }
    }

    private companion object {
        // 1 回で送る応答の上限(残りは次の回)
        const val DEFAULT_BATCH_SIZE = 100
    }
}
