package io.eia.order.adapters.out.saga

import io.eia.order.adapters.out.outbox.AddressV1
import io.eia.order.adapters.out.outbox.MoneyV1
import io.eia.order.adapters.out.persistence.currentTransaction
import io.eia.order.application.port.outbound.SagaCommandOutbox
import io.eia.order.domain.Order
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaCommand
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
 * [SagaCommandOutbox] の実装。Saga の更新と同じ Exposed のトランザクションで、`platform/outbox` の Outbox に書く(ADR-0007・ADR-0029 §1)。
 * `aggregate_type` は `saga`、`aggregate_id`(Kafka のキー)は Saga ID(同じ Saga のコマンドは同じパーティションに順に入る。ADR-0006)。
 * コマンドの中身(明細・金額・届け先)は注文から作る。
 */
public class OutboxSagaCommands(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val serializers: SagaSchemas.Serializers,
) : SagaCommandOutbox {
    override suspend fun send(
        command: SagaCommand,
        saga: Saga,
        order: Order,
    ): Result<Unit, DomainError> {
        val sagaId = saga.id
        val orderId = order.id.value
        return when (command) {
            SagaCommand.RESERVE_STOCK -> {
                append(
                    serializers.reserve,
                    sagaId,
                    ReserveStockV1(
                        sagaId,
                        orderId,
                        order.lines.map {
                            StockLineV1(it.lineNumber, it.sku.value, it.quantity)
                        },
                    ),
                )
            }

            SagaCommand.RELEASE_STOCK -> {
                append(serializers.release, sagaId, ReleaseStockV1(sagaId, orderId))
            }

            SagaCommand.AUTHORIZE_PAYMENT -> {
                val amount = MoneyV1(order.totalAmount.minorUnits, order.totalAmount.currency.code)
                append(serializers.authorize, sagaId, AuthorizePaymentV1(sagaId, orderId, order.customerId.value, amount))
            }

            SagaCommand.VOID_PAYMENT -> {
                append(serializers.void, sagaId, VoidPaymentV1(sagaId, orderId))
            }

            SagaCommand.ARRANGE_SHIPMENT -> {
                val address =
                    order.shippingAddress.let { AddressV1(it.countryCode, it.postalCode, it.city, it.line1, it.region, it.line2) }
                val lines = order.lines.map { ShipmentLineV1(it.lineNumber, it.sku.value, it.quantity) }
                append(serializers.arrange, sagaId, ArrangeShipmentV1(sagaId, orderId, address, lines))
            }

            SagaCommand.CANCEL_SHIPMENT -> {
                append(serializers.cancel, sagaId, CancelShipmentV1(sagaId, orderId))
            }
        }
    }

    private suspend fun <T> append(
        serializer: AvroEventSerializer<T>,
        sagaId: String,
        value: T,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("Saga のコマンドは、Saga の更新と同じトランザクションの中で書いてください"))
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
