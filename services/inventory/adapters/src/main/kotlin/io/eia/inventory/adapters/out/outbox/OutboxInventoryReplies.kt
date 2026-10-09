package io.eia.inventory.adapters.out.outbox

import io.eia.inventory.adapters.out.persistence.currentTransaction
import io.eia.inventory.application.port.outbound.InventoryReplies
import io.eia.inventory.domain.RejectionReason
import io.eia.inventory.domain.ReleaseOutcome
import io.eia.inventory.domain.ReserveReply
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [InventoryReplies] の実装。引当の記録と同じ Exposed のトランザクションで、`platform/outbox` の Outbox に書く(ADR-0007)。
 * `aggregate_type` は `saga`、`aggregate_id`(Kafka のキー)は Saga ID(ADR-0029 §4)。
 */
public class OutboxInventoryReplies(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val serializers: InventoryEventSchemas.Serializers,
) : InventoryReplies {
    override suspend fun reserveReplied(
        sagaId: String,
        orderId: String,
        reply: ReserveReply,
    ): Result<Unit, DomainError> =
        when (reply) {
            ReserveReply.Reserved -> {
                append(serializers.reserved, sagaId, StockReservedV1(sagaId, orderId))
            }

            is ReserveReply.Rejected -> {
                append(
                    serializers.rejected,
                    sagaId,
                    StockReservationRejectedV1(sagaId, orderId, reasonOf(reply.reason)),
                )
            }
        }

    override suspend fun released(
        sagaId: String,
        orderId: String,
        outcome: ReleaseOutcome,
    ): Result<Unit, DomainError> {
        val value =
            when (outcome) {
                ReleaseOutcome.RELEASED -> StockReleaseOutcomeV1.RELEASED
                ReleaseOutcome.NOT_RESERVED -> StockReleaseOutcomeV1.NOT_RESERVED
            }
        return append(serializers.released, sagaId, StockReleasedV1(sagaId, orderId, value))
    }

    private suspend fun <T> append(
        serializer: AvroEventSerializer<T>,
        sagaId: String,
        value: T,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("返事のイベントは、引当の記録と同じトランザクションの中で書いてください"))
        val appended =
            events
                .create(serializer, AGGREGATE_TYPE, sagaId, value)
                .mapError { it.asDomainError() }
                .flatMap { record -> outbox.appendOutbox(transaction, listOf(record)).mapError { it.asDomainError() } }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える
        currentCoroutineContext().ensureActive()
        return appended
    }

    private fun reasonOf(reason: RejectionReason): StockRejectionReasonV1 =
        when (reason) {
            RejectionReason.INSUFFICIENT_STOCK -> StockRejectionReasonV1.INSUFFICIENT_STOCK
            RejectionReason.UNKNOWN_SKU -> StockRejectionReasonV1.UNKNOWN_SKU
            RejectionReason.ALREADY_RELEASED -> StockRejectionReasonV1.ALREADY_RELEASED
        }

    private companion object {
        const val AGGREGATE_TYPE = "saga"
    }
}
