package io.eia.shipping.adapters.inbound

import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventSubscription
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.eia.shipping.adapters.out.persistence.TransientSqlError
import io.eia.shipping.application.port.inbound.ArrangeShipmentUseCase
import io.eia.shipping.application.port.inbound.CancelShipmentUseCase
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.inbound.CommandOutcome
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.ShipmentLine

/**
 * shipping のコマンド(INT-SHIPPING-001)の購読。Consumer Group は `shipping.command` だけ(ADR-0006)。
 * ユースケースのエラーを、Consumer の失敗の種類に写す(ADR-0028 §2。inventory と同じ規則)。
 */
public class ShippingCommandHandlers(
    private val arrange: ArrangeShipmentUseCase,
    private val cancel: CancelShipmentUseCase,
) {
    public fun subscriptions(writerSchemas: WriterSchemas): List<EventSubscription<*>> =
        listOf(
            EventSubscription(ARRANGE, AvroEventDeserializer(ArrangeShipmentV1.serializer(), writerSchemas), INTEGRATION_ID, ::onArrange),
            EventSubscription(CANCEL, AvroEventDeserializer(CancelShipmentV1.serializer(), writerSchemas), INTEGRATION_ID, ::onCancel),
        )

    internal suspend fun onArrange(event: ConsumedEvent<ArrangeShipmentV1>): Result<Handled, HandlingFailure> {
        val command = event.value
        return Destination
            .of(command.shippingAddress.countryCode)
            .mapError { it as DomainError }
            .flatMap { destination -> lines(command.lines).map { destination to it } }
            .flatMap { (destination, lines) -> arrange(envelope(event, command.sagaId, command.orderId), destination, lines) }
            .toHandling()
    }

    internal suspend fun onCancel(event: ConsumedEvent<CancelShipmentV1>): Result<Handled, HandlingFailure> =
        cancel(envelope(event, event.value.sagaId, event.value.orderId)).toHandling()

    private fun envelope(
        event: ConsumedEvent<*>,
        sagaId: String,
        orderId: String,
    ) = CommandEnvelope(event.metadata.id.toString(), event.topic, sagaId, orderId)

    private fun lines(lines: List<ShipmentLineV1>): Result<List<ShipmentLine>, DomainError> {
        val parsed = lines.map { ShipmentLine.of(it.lineNumber, it.sku, it.quantity) }
        val violations = parsed.filterIsInstance<Result.Err<ValidationError>>().flatMap { it.error.violations }.distinct()
        return if (violations.isEmpty()) ok(parsed.map { (it as Result.Ok).value }) else err(ValidationError(violations))
    }

    public companion object {
        public val ARRANGE: EventTopic = EventTopic.of("shipping.shipment.cmd-arrange.v1")
        public val CANCEL: EventTopic = EventTopic.of("shipping.shipment.cmd-cancel.v1")
        public const val GROUP_ID: String = "shipping.command"
        public const val INTEGRATION_ID: String = "INT-SHIPPING-001"

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
