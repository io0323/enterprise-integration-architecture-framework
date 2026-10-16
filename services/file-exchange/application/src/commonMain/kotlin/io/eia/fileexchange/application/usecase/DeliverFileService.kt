package io.eia.fileexchange.application.usecase

import io.eia.fileexchange.application.port.inbound.DeliverFileUseCase
import io.eia.fileexchange.application.port.inbound.DeliveryReport
import io.eia.fileexchange.application.port.inbound.PartnerDelivery
import io.eia.fileexchange.application.port.inbound.PartnerFailure
import io.eia.fileexchange.application.port.outbound.Deliveries
import io.eia.fileexchange.application.port.outbound.PartnerOutbox
import io.eia.fileexchange.application.port.outbound.Partners
import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.domain.DatasetKey
import io.eia.fileexchange.domain.Delivery
import io.eia.fileexchange.domain.DeliveryOutcome
import io.eia.fileexchange.domain.DeliveryState
import io.eia.fileexchange.domain.PartnerId
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

public class DeliverFileService(
    private val files: ReceivedFiles,
    private val partners: Partners,
    private val deliveries: Deliveries,
    private val outbox: PartnerOutbox,
) : DeliverFileUseCase {
    @Suppress("ReturnCount") // 記録の読み込みの失敗と、受け取りが終わっていないときに返す
    override suspend fun invoke(name: String): Result<DeliveryReport, DomainError> {
        val file =
            when (val found = files.find(name)) {
                is Result.Err -> return found
                is Result.Ok -> found.value
            }
        if (file == null || file.state != ReceiptState.STORED) return err(ConflictError("$name の受け取りが終わっていません"))
        if (file.destination == ReceiptDestination.QUARANTINE) return ok(DeliveryReport(name, true, emptyList(), emptyList()))
        val dataFile = checkNotNull(file.dataFile)
        val delivered = mutableListOf<PartnerDelivery>()
        val failures = mutableListOf<PartnerFailure>()
        DatasetKey.of(name)?.let(partners::recipients).orEmpty().forEach { partner ->
            when (val one = deliver(name, partner, dataFile)) {
                is Result.Ok -> delivered += PartnerDelivery(partner, one.value)
                is Result.Err -> failures += PartnerFailure(partner, one.error)
            }
        }
        return ok(DeliveryReport(name, false, delivered, failures))
    }

    /**
     * 1 つの取引先への配送。送る前に「送信中」を記録し、相手の側に正式名があれば送らない(ADR-0032 §8)。
     * 正式名に変えた後、送信済みを記録する前に落ちても、やり直しで同じファイルを送り直さない。
     */
    @Suppress("ReturnCount") // 記録・確認・送信の失敗のそれぞれで返す
    private suspend fun deliver(
        name: String,
        partner: PartnerId,
        dataFile: String,
    ): Result<DeliveryOutcome, DomainError> {
        val existing =
            when (val found = deliveries.find(name, partner)) {
                is Result.Err -> return found
                is Result.Ok -> found.value
            }
        if (existing?.state == DeliveryState.SENT) return ok(DeliveryOutcome.ALREADY_SENT)
        val delivery =
            existing ?: when (val begun = deliveries.begin(name, partner, Delivery.remoteName(dataFile))) {
                is Result.Err -> return begun
                is Result.Ok -> begun.value
            }
        val present =
            when (val checked = outbox.exists(partner, delivery.remoteName)) {
                is Result.Err -> return checked
                is Result.Ok -> checked.value
            }
        if (!present) {
            val sent = outbox.send(partner, name, dataFile, delivery.remoteName)
            if (sent is Result.Err) return sent
        }
        return when (val done = deliveries.sent(name, partner, alreadyPresent = present)) {
            is Result.Err -> done
            is Result.Ok -> ok(if (present) DeliveryOutcome.ALREADY_PRESENT else DeliveryOutcome.SENT)
        }
    }
}
