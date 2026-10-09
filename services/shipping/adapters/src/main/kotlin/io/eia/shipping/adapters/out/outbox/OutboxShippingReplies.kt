package io.eia.shipping.adapters.out.outbox

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
import io.eia.shipping.adapters.out.persistence.currentTransaction
import io.eia.shipping.application.port.outbound.ShippingReplies
import io.eia.shipping.domain.ArrangeReply
import io.eia.shipping.domain.CancelOutcome
import io.eia.shipping.domain.RejectionReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [ShippingReplies] の実装。出荷の記録と同じ Exposed のトランザクションで、`platform/outbox` の Outbox に書く(ADR-0007)。
 * `aggregate_type` は `saga`、`aggregate_id`(Kafka のキー)は Saga ID(ADR-0029 §4)。
 */
public class OutboxShippingReplies(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val serializers: ShippingEventSchemas.Serializers,
) : ShippingReplies {
    override suspend fun arrangeReplied(
        sagaId: String,
        orderId: String,
        reply: ArrangeReply,
    ): Result<Unit, DomainError> =
        when (reply) {
            is ArrangeReply.Shipped -> {
                append(serializers.shipped, sagaId, ShipmentShippedV1(sagaId, orderId, reply.shipmentId, reply.shippedAt))
            }

            is ArrangeReply.Rejected -> {
                val reason =
                    when (reply.reason) {
                        RejectionReason.UNSUPPORTED_DESTINATION -> ShipmentRejectionReasonV1.UNSUPPORTED_DESTINATION
                        RejectionReason.ALREADY_CANCELLED -> ShipmentRejectionReasonV1.ALREADY_CANCELLED
                    }
                append(serializers.rejected, sagaId, ShipmentRejectedV1(sagaId, orderId, reason))
            }
        }

    override suspend fun cancelled(
        sagaId: String,
        orderId: String,
        outcome: CancelOutcome,
    ): Result<Unit, DomainError> {
        val value =
            when (outcome) {
                CancelOutcome.CANCELLED -> ShipmentCancelOutcomeV1.CANCELLED
                CancelOutcome.NOT_ARRANGED -> ShipmentCancelOutcomeV1.NOT_ARRANGED
                CancelOutcome.ALREADY_SHIPPED -> ShipmentCancelOutcomeV1.ALREADY_SHIPPED
            }
        return append(serializers.cancelled, sagaId, ShipmentCancelledV1(sagaId, orderId, value))
    }

    private suspend fun <T> append(
        serializer: AvroEventSerializer<T>,
        sagaId: String,
        value: T,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("返事のイベントは、出荷の記録と同じトランザクションの中で書いてください"))
        val appended =
            events
                .create(serializer, AGGREGATE_TYPE, sagaId, value)
                .mapError { it.asDomainError() }
                .flatMap { record -> outbox.appendOutbox(transaction, listOf(record)).mapError { it.asDomainError() } }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える
        currentCoroutineContext().ensureActive()
        return appended
    }

    private companion object {
        const val AGGREGATE_TYPE = "saga"
    }
}
