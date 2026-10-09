package io.eia.payment.adapters.inbound

import io.eia.payment.adapters.out.persistence.TransientSqlError
import io.eia.payment.application.port.inbound.AuthorizePaymentUseCase
import io.eia.payment.application.port.inbound.CommandEnvelope
import io.eia.payment.application.port.inbound.CommandOutcome
import io.eia.payment.application.port.inbound.VoidPaymentUseCase
import io.eia.payment.domain.Amount
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventSubscription
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/**
 * payment のコマンド(INT-PAYMENT-001)の購読。Consumer Group は `payment.command` だけ(ADR-0006)。
 * ユースケースのエラーを、Consumer の失敗の種類に写す(ADR-0028 §2。inventory と同じ規則)。
 */
public class PaymentCommandHandlers(
    private val authorize: AuthorizePaymentUseCase,
    private val void: VoidPaymentUseCase,
) {
    public fun subscriptions(writerSchemas: WriterSchemas): List<EventSubscription<*>> =
        listOf(
            EventSubscription(
                AUTHORIZE,
                AvroEventDeserializer(AuthorizePaymentV1.serializer(), writerSchemas),
                INTEGRATION_ID,
                ::onAuthorize,
            ),
            EventSubscription(VOID, AvroEventDeserializer(VoidPaymentV1.serializer(), writerSchemas), INTEGRATION_ID, ::onVoid),
        )

    internal suspend fun onAuthorize(event: ConsumedEvent<AuthorizePaymentV1>): Result<Handled, HandlingFailure> {
        val command = event.value
        return Amount
            .of(command.amount.minorUnits, command.amount.currency)
            .mapError { it as DomainError }
            .flatMap { amount -> authorize(envelope(event, command.sagaId, command.orderId), command.customerId, amount) }
            .toHandling()
    }

    internal suspend fun onVoid(event: ConsumedEvent<VoidPaymentV1>): Result<Handled, HandlingFailure> =
        void(envelope(event, event.value.sagaId, event.value.orderId)).toHandling()

    private fun envelope(
        event: ConsumedEvent<*>,
        sagaId: String,
        orderId: String,
    ) = CommandEnvelope(event.metadata.id.toString(), event.topic, sagaId, orderId)

    public companion object {
        public val AUTHORIZE: EventTopic = EventTopic.of("payment.payment.cmd-authorize.v1")
        public val VOID: EventTopic = EventTopic.of("payment.payment.cmd-void.v1")
        public const val GROUP_ID: String = "payment.command"
        public const val INTEGRATION_ID: String = "INT-PAYMENT-001"

        internal fun Result<CommandOutcome, DomainError>.toHandling(): Result<Handled, HandlingFailure> =
            map { outcome ->
                when (outcome) {
                    CommandOutcome.PROCESSED -> Handled.PROCESSED
                    CommandOutcome.DUPLICATE -> Handled.DUPLICATE
                }
            }.mapError { error ->
                when (error) {
                    is TransientSqlError -> HandlingFailure.Transient(error.code, error.message)
                    is DomainError.Retryable -> HandlingFailure.Unavailable(error.code, error.message)
                    is DomainError.NonRetryable -> HandlingFailure.Rejected(error.code, error.message)
                }
            }
    }
}
