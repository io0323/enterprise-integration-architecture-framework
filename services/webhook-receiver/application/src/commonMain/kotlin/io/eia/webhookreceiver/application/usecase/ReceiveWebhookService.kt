package io.eia.webhookreceiver.application.usecase

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhook
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhookUseCase
import io.eia.webhookreceiver.application.port.outbound.IngestOutbox
import io.eia.webhookreceiver.application.port.outbound.TransactionRunner
import io.eia.webhookreceiver.application.port.outbound.WebhookReceipts
import io.eia.webhookreceiver.domain.ReceiptOutcome
import io.eia.webhookreceiver.domain.WebhookReceipt
import kotlin.time.Clock

/**
 * [ReceiveWebhookUseCase] の実装。記録がなければ、受け取りの記録と Outbox を 1 つのトランザクションで書く(ADR-0035 §3)。
 * 記録済みの ID なら何も書かない(Webhook の再送は At-Least-Once。重複は ID で判定する)。
 */
public class ReceiveWebhookService(
    private val transactions: TransactionRunner,
    private val receipts: WebhookReceipts,
    private val outbox: IngestOutbox,
    private val clock: Clock = Clock.System,
) : ReceiveWebhookUseCase {
    override suspend fun invoke(command: ReceiveWebhook): Result<ReceiptOutcome, DomainError> =
        WebhookReceipt
            .of(command.webhookId, command.source, command.bodySha256, command.signedAt, clock.now())
            .mapError { it as DomainError }
            .flatMap { receipt ->
                transactions.inTransaction {
                    receipts.insertIfAbsent(receipt).flatMap { existing ->
                        when (val outcome = receipt.against(existing)) {
                            ReceiptOutcome.Recorded -> outbox.received(receipt, command.contentType, command.body).map { outcome }
                            is ReceiptOutcome.Duplicate -> ok(outcome)
                        }
                    }
                }
            }
}
