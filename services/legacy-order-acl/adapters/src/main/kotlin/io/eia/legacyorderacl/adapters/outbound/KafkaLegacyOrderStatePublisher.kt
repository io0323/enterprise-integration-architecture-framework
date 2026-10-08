package io.eia.legacyorderacl.adapters.outbound

import io.eia.legacyorderacl.application.port.inbound.ChangePosition
import io.eia.legacyorderacl.application.port.outbound.LegacyOrderStatePublisher
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.legacyorderacl.domain.LegacyOrderStatus
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/**
 * [LegacyOrderStatePublisher] の Kafka の実装。`sales.legacy-order.changed.v1`(compacted)に、注文番号をキーにして送る。
 * 送信の完了(acks=all)まで待つので、戻ったときは発行が確定している(ADR-0026 §6 の At-Least-Once の前提)。
 * 削除は tombstone(値のないレコード)。ヘッダは [EventProducer] が付ける(ce_source は `/sales/legacy-order-acl`)。
 */
public class KafkaLegacyOrderStatePublisher(
    private val producer: EventProducer,
    ids: SchemaIdBook,
) : LegacyOrderStatePublisher {
    private val serializer = LegacyOrderEventSchemas.legacyOrderChangedSerializer(ids)

    override suspend fun upsert(
        order: LegacyOrder,
        position: ChangePosition,
    ): Result<Unit, DomainError> =
        producer
            .send(serializer, order.orderNumber, order.toEvent(position))
            .mapError { it.asDomainError() }
            .map { }

    override suspend fun delete(
        orderNumber: String,
        position: ChangePosition,
    ): Result<Unit, DomainError> =
        producer
            .sendTombstone(LegacyOrderEventSchemas.LEGACY_ORDER_CHANGED, orderNumber)
            .mapError { it.asDomainError() }
            .map { }

    public companion object {
        /** `ce_source`。 */
        public const val SOURCE: String = "/sales/legacy-order-acl"
    }
}

internal fun LegacyOrder.toEvent(position: ChangePosition): LegacyOrderChangedV1 =
    LegacyOrderChangedV1(
        orderNumber = orderNumber,
        customerCode = customerCode,
        customerName = customerName,
        status =
            when (status) {
                LegacyOrderStatus.ACCEPTED -> LegacyOrderStatusV1.ACCEPTED
                LegacyOrderStatus.ALLOCATED -> LegacyOrderStatusV1.ALLOCATED
                LegacyOrderStatus.SHIPPED -> LegacyOrderStatusV1.SHIPPED
                LegacyOrderStatus.CANCELLED -> LegacyOrderStatusV1.CANCELLED
            },
        totalAmount = MoneyV1(totalAmount.minorUnits, totalAmount.currency.code),
        orderedAt = orderedAt,
        legacyUpdatedAt = legacyUpdatedAt,
        source = LegacyChangeSourceV1(position.lsn, position.committedAt, position.snapshot),
    )
