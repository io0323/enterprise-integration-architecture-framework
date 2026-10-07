package io.eia.order.adapters.out.outbox

import io.eia.order.adapters.out.persistence.currentTransaction
import io.eia.order.application.port.outbound.OrderEventOutbox
import io.eia.order.domain.Order
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
    private val serializer: AvroEventSerializer<OrderCreatedV1>,
) : OrderEventOutbox {
    override suspend fun orderPlaced(order: Order): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("注文のイベントは、注文の保存と同じトランザクションの中で書いてください"))
        val appended =
            OrderEventMapper
                .orderCreated(order)
                .mapError { it as DomainError }
                .flatMap { event -> events.create(serializer, AGGREGATE_TYPE, order.id.value, event).mapError { it.asDomainError() } }
                .flatMap { record -> outbox.appendOutbox(transaction, listOf(record)).mapError { it.asDomainError() } }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える(ADR-0024 §3)
        currentCoroutineContext().ensureActive()
        return appended
    }

    private companion object {
        const val AGGREGATE_TYPE = "order"
    }
}
