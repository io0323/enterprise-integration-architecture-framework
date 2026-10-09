package io.eia.order.adapters.inbound.kafka

import io.eia.order.application.port.inbound.HandleSagaReplyUseCase
import io.eia.order.application.port.inbound.ReplyOutcome
import io.eia.order.application.port.inbound.SagaReply
import io.eia.order.domain.SagaSignal
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventSubscription
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import kotlinx.serialization.KSerializer

/**
 * 参加者の返信(INT-INVENTORY/PAYMENT/SHIPPING-002)の購読。Consumer Group は `order.saga`(ADR-0029 §2)。
 *
 * - 返信を [SagaSignal] にして、Saga を進める([HandleSagaReplyUseCase])。
 * - `reason` / `outcome` が `UNKNOWN`(読み手の知らない値)なら判定できないので、Rejected(DLQ)にする(ADR-0029 §4)。
 * - ユースケースのエラーは、Retryable(DB に接続できない・Outbox に書けない)なら Unavailable(読み直し)、NonRetryable(知らない Saga など)なら
 *   Rejected(DLQ)にする(ADR-0028 §2)。
 */
public class SagaReplyHandlers(
    private val handle: HandleSagaReplyUseCase,
) {
    public fun subscriptions(writerSchemas: WriterSchemas): List<EventSubscription<*>> =
        listOf(
            subscription(
                STOCK_RESERVED,
                StockReservedV1.serializer(),
                writerSchemas,
                INVENTORY,
            ) { ok(it.sagaId, SagaSignal.STOCK_RESERVED) },
            subscription(STOCK_RESERVATION_REJECTED, StockReservationRejectedV1.serializer(), writerSchemas, INVENTORY) {
                known(it.sagaId, it.reason == StockRejectionReasonV1.UNKNOWN, SagaSignal.STOCK_RESERVATION_REJECTED)
            },
            subscription(STOCK_RELEASED, StockReleasedV1.serializer(), writerSchemas, INVENTORY) {
                known(it.sagaId, it.outcome == StockReleaseOutcomeV1.UNKNOWN, SagaSignal.STOCK_RELEASED)
            },
            subscription(PAYMENT_AUTHORIZED, PaymentAuthorizedV1.serializer(), writerSchemas, PAYMENT) {
                ok(it.sagaId, SagaSignal.PAYMENT_AUTHORIZED)
            },
            subscription(PAYMENT_DECLINED, PaymentDeclinedV1.serializer(), writerSchemas, PAYMENT) {
                known(it.sagaId, it.reason == PaymentDeclineReasonV1.UNKNOWN, SagaSignal.PAYMENT_DECLINED)
            },
            subscription(PAYMENT_VOIDED, PaymentVoidedV1.serializer(), writerSchemas, PAYMENT) {
                known(it.sagaId, it.outcome == PaymentVoidOutcomeV1.UNKNOWN, SagaSignal.PAYMENT_VOIDED)
            },
            subscription(SHIPMENT_SHIPPED, ShipmentShippedV1.serializer(), writerSchemas, SHIPPING) {
                ok(it.sagaId, SagaSignal.SHIPMENT_SHIPPED)
            },
            subscription(SHIPMENT_REJECTED, ShipmentRejectedV1.serializer(), writerSchemas, SHIPPING) {
                known(it.sagaId, it.reason == ShipmentRejectionReasonV1.UNKNOWN, SagaSignal.SHIPMENT_REJECTED)
            },
            subscription(SHIPMENT_CANCELLED, ShipmentCancelledV1.serializer(), writerSchemas, SHIPPING) {
                when (it.outcome) {
                    ShipmentCancelOutcomeV1.CANCELLED, ShipmentCancelOutcomeV1.NOT_ARRANGED -> ok(it.sagaId, SagaSignal.SHIPMENT_CANCELLED)
                    ShipmentCancelOutcomeV1.ALREADY_SHIPPED -> ok(it.sagaId, SagaSignal.SHIPMENT_ALREADY_SHIPPED)
                    ShipmentCancelOutcomeV1.UNKNOWN -> unknown()
                }
            },
        )

    /** 返信 1 件を Saga ID と受け取ったもの(または DLQ に送る理由)にする関数。 */
    internal fun interface ToSignal<T> {
        fun of(value: T): Result<Pair<String, SagaSignal>, HandlingFailure>
    }

    private fun <T> subscription(
        topic: EventTopic,
        serializer: KSerializer<T>,
        writerSchemas: WriterSchemas,
        integrationId: String,
        toSignal: ToSignal<T>,
    ): EventSubscription<T> =
        EventSubscription(topic, AvroEventDeserializer(serializer, writerSchemas), integrationId) { event -> onReply(event, toSignal) }

    internal suspend fun <T> onReply(
        event: ConsumedEvent<T>,
        toSignal: ToSignal<T>,
    ): Result<Handled, HandlingFailure> =
        when (val signal = toSignal.of(event.value)) {
            is Result.Err -> {
                signal
            }

            is Result.Ok -> {
                val (sagaId, value) = signal.value
                handle(SagaReply(event.metadata.id.toString(), event.topic, sagaId, value)).toHandling()
            }
        }

    public companion object {
        public const val GROUP_ID: String = "order.saga"
        private const val INVENTORY = "INT-INVENTORY-002"
        private const val PAYMENT = "INT-PAYMENT-002"
        private const val SHIPPING = "INT-SHIPPING-002"
        public val STOCK_RESERVED: EventTopic = EventTopic.of("inventory.stock.reserved.v1")
        public val STOCK_RESERVATION_REJECTED: EventTopic = EventTopic.of("inventory.stock.reservation-rejected.v1")
        public val STOCK_RELEASED: EventTopic = EventTopic.of("inventory.stock.released.v1")
        public val PAYMENT_AUTHORIZED: EventTopic = EventTopic.of("payment.payment.authorized.v1")
        public val PAYMENT_DECLINED: EventTopic = EventTopic.of("payment.payment.declined.v1")
        public val PAYMENT_VOIDED: EventTopic = EventTopic.of("payment.payment.voided.v1")
        public val SHIPMENT_SHIPPED: EventTopic = EventTopic.of("shipping.shipment.shipped.v1")
        public val SHIPMENT_REJECTED: EventTopic = EventTopic.of("shipping.shipment.rejected.v1")
        public val SHIPMENT_CANCELLED: EventTopic = EventTopic.of("shipping.shipment.cancelled.v1")

        /** DLQ の `eiaf.dlq.reason`(小文字のコード。Consumer が大文字にする)。 */
        internal const val UNKNOWN_VALUE = "unknown_reply_value"

        private fun ok(
            sagaId: String,
            signal: SagaSignal,
        ): Result<Pair<String, SagaSignal>, HandlingFailure> = Result.Ok(sagaId to signal)

        private fun unknown(): Result<Pair<String, SagaSignal>, HandlingFailure> =
            Result.Err(HandlingFailure.Rejected(UNKNOWN_VALUE, "reason / outcome: 読み手の知らない値(UNKNOWN)のため判定できない"))

        private fun known(
            sagaId: String,
            isUnknown: Boolean,
            signal: SagaSignal,
        ): Result<Pair<String, SagaSignal>, HandlingFailure> = if (isUnknown) unknown() else ok(sagaId, signal)

        internal fun Result<ReplyOutcome, DomainError>.toHandling(): Result<Handled, HandlingFailure> =
            map { outcome ->
                when (outcome) {
                    ReplyOutcome.PROCESSED -> Handled.PROCESSED
                    ReplyOutcome.DUPLICATE -> Handled.DUPLICATE
                }
            }.mapError { error ->
                when (error) {
                    is DomainError.Retryable -> HandlingFailure.Unavailable(error.code, error.message)
                    is DomainError.NonRetryable -> HandlingFailure.Rejected(error.code, error.message)
                }
            }
    }
}
