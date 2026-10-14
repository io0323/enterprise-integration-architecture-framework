package io.eia.order.adapters.out.outbox

import io.eia.order.adapters.out.persistence.currentTransaction
import io.eia.order.application.port.outbound.OrderEventOutbox
import io.eia.order.domain.Order
import io.eia.order.domain.OrderStatus
import io.eia.order.domain.SagaFailure
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
import kotlin.time.Clock

/**
 * [OrderEventOutbox] の実装。注文の保存と同じ Exposed のトランザクションで、`platform/outbox` の Outbox に書く(ADR-0007)。
 *
 * - 今のトランザクションがなければ [OutboxMisuse] にする(別のトランザクションで書くと、業務の更新とイベントの確定がずれるため)。
 * - イベントは [OrderEventMapper] で Canonical Model を経由して作り、[serializer](契約のスキーマ・起動時に解決した ID)で Avro にする。
 * - `aggregate_type` は `order`、`aggregate_id`(Kafka のキー)は注文 ID。同じ注文のイベントは同じパーティションに入り、順序が保たれる。
 */
public class OutboxOrderEvents(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val serializers: Serializers,
    private val clock: Clock = Clock.System,
) : OrderEventOutbox {
    /** 書くイベントの Serializer(契約のスキーマと、起動時に解決した ID)。 */
    public class Serializers(
        public val created: AvroEventSerializer<OrderCreatedV1>,
        public val cancelled: AvroEventSerializer<OrderCancelledV1>,
        public val statusChanged: AvroEventSerializer<OrderStatusChangedV1>,
    )

    override suspend fun orderPlaced(order: Order): Result<Unit, DomainError> =
        OrderEventMapper
            .orderCreated(order)
            .mapError { it as DomainError }
            .flatMap { event -> append(serializers.created, order, event) }

    /** 注文の状態の遷移(`sales.order.status-changed.v1`。ADR-0031 §2)。版は遷移の後の版。 */
    override suspend fun orderStatusChanged(
        order: Order,
        from: OrderStatus,
    ): Result<Unit, DomainError> {
        val event =
            OrderStatusChangedV1(
                orderId = order.id.value,
                status = OrderStatusV1.valueOf(order.status.name),
                previousStatus = OrderStatusV1.valueOf(from.name),
                version = order.version,
                changedAt = clock.now(),
            )
        return append(serializers.statusChanged, order, event)
    }

    /** 注文の取消(`sales.order.cancelled.v1`。ADR-0029 §8)。理由は Saga の補償の理由の名前(個人情報を含めない)。 */
    override suspend fun orderCancelled(
        order: Order,
        reason: SagaFailure?,
    ): Result<Unit, DomainError> =
        append(serializers.cancelled, order, OrderCancelledV1(order.id.value, clock.now(), reason?.name ?: UNKNOWN_REASON))

    /** 今のトランザクション(注文の保存と同じ)で Outbox に書く。キーは注文 ID(同じ注文のイベントは同じパーティションに入る)。 */
    private suspend fun <T> append(
        serializer: AvroEventSerializer<T>,
        order: Order,
        event: T,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("注文のイベントは、注文の保存と同じトランザクションの中で書いてください"))
        val appended =
            events
                .create(serializer, AGGREGATE_TYPE, order.id.value, event)
                .mapError { it.asDomainError() }
                .flatMap { record -> outbox.appendOutbox(transaction, listOf(record)).mapError { it.asDomainError() } }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える(ADR-0024 §3)
        currentCoroutineContext().ensureActive()
        return appended
    }

    private companion object {
        const val UNKNOWN_REASON = "UNKNOWN"
        const val AGGREGATE_TYPE = "order"
    }
}
