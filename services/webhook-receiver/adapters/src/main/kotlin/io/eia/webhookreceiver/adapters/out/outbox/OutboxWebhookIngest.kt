package io.eia.webhookreceiver.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.outbox.OutboxMisuse
import io.eia.platform.outbox.appendOutbox
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import io.eia.webhookreceiver.adapters.out.persistence.currentTransaction
import io.eia.webhookreceiver.application.port.outbound.IngestOutbox
import io.eia.webhookreceiver.domain.WebhookReceipt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [IngestOutbox] の実装。受け取りの記録と同じ Exposed のトランザクションで、`platform/outbox` の Outbox に書く(ADR-0007)。
 *
 * - `aggregate_type` は `webhook`、`aggregate_id`(Kafka のキー)は webhook-id(INT-INTEGRATION-001 のパーティションキー)。
 * - `ce_id` は Outbox が受け取りごとに 1 つ作る(UUIDv7)。同じ webhook-id の再送は記録しないので、1 つの Webhook の `ce_id` は 1 つ。
 *   取り込みの側は `ce_id` で冪等に処理する(platform/inbox。ADR-0036)。
 */
public class OutboxWebhookIngest(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val serializer: AvroEventSerializer<WebhookReceivedV1>,
) : IngestOutbox {
    override suspend fun received(
        receipt: WebhookReceipt,
        contentType: String,
        body: ByteArray,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("取り込みのイベントは、受け取りの記録と同じトランザクションの中で書いてください"))
        val event =
            WebhookReceivedV1(
                webhookId = receipt.webhookId,
                source = receipt.source,
                signedAt = receipt.signedAt,
                receivedAt = receipt.receivedAt,
                contentType = contentType,
                bodySha256 = receipt.bodySha256,
                body = body,
            )
        val appended =
            events
                .create(serializer, AGGREGATE_TYPE, receipt.webhookId, event)
                .mapError { it.asDomainError() }
                .flatMap { record -> outbox.appendOutbox(transaction, listOf(record)).mapError { it.asDomainError() } }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える
        currentCoroutineContext().ensureActive()
        return appended
    }

    private companion object {
        const val AGGREGATE_TYPE = "webhook"
    }
}
