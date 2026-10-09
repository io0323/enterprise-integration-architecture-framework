package io.eia.payment.adapters.out.outbox

import io.eia.payment.adapters.out.persistence.currentTransaction
import io.eia.payment.application.port.outbound.PaymentReplies
import io.eia.payment.domain.AuthorizeReply
import io.eia.payment.domain.DeclineReason
import io.eia.payment.domain.VoidOutcome
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
 * [PaymentReplies] の実装。承認の記録と同じ Exposed のトランザクションで、`platform/outbox` の Outbox に書く(ADR-0007)。
 * `aggregate_type` は `saga`、`aggregate_id`(Kafka のキー)は Saga ID(ADR-0029 §4)。
 */
public class OutboxPaymentReplies(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val serializers: PaymentEventSchemas.Serializers,
) : PaymentReplies {
    override suspend fun authorizeReplied(
        sagaId: String,
        orderId: String,
        reply: AuthorizeReply,
    ): Result<Unit, DomainError> =
        when (reply) {
            is AuthorizeReply.Authorized -> {
                append(serializers.authorized, sagaId, PaymentAuthorizedV1(sagaId, orderId, reply.authorizationId))
            }

            is AuthorizeReply.Declined -> {
                val reason =
                    when (reply.reason) {
                        DeclineReason.LIMIT_EXCEEDED -> PaymentDeclineReasonV1.LIMIT_EXCEEDED
                        DeclineReason.ALREADY_VOIDED -> PaymentDeclineReasonV1.ALREADY_VOIDED
                    }
                append(serializers.declined, sagaId, PaymentDeclinedV1(sagaId, orderId, reason))
            }
        }

    override suspend fun voided(
        sagaId: String,
        orderId: String,
        outcome: VoidOutcome,
    ): Result<Unit, DomainError> {
        val value =
            when (outcome) {
                VoidOutcome.VOIDED -> PaymentVoidOutcomeV1.VOIDED
                VoidOutcome.NOT_AUTHORIZED -> PaymentVoidOutcomeV1.NOT_AUTHORIZED
            }
        return append(serializers.voided, sagaId, PaymentVoidedV1(sagaId, orderId, value))
    }

    private suspend fun <T> append(
        serializer: AvroEventSerializer<T>,
        sagaId: String,
        value: T,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("返事のイベントは、承認の記録と同じトランザクションの中で書いてください"))
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
