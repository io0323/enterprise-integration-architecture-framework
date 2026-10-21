package io.eia.integrationflow.adapters.out.outbox

import io.eia.integrationflow.adapters.canonical.CanonicalTypes
import io.eia.integrationflow.adapters.out.persistence.currentTransaction
import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.application.port.outbound.CanonicalPublisher
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.outbox.OutboxMisuse
import io.eia.platform.outbox.appendOutbox
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [CanonicalPublisher] の実装。写した結果を Canonical の型に変え(検証を含む。[CanonicalTypes])、契約のイベントにして、
 * 新旧の判定の記録と同じ Exposed のトランザクションで Outbox に書く(ADR-0007)。
 *
 * - `aggregate_type` は Canonical の型(例 `customer`)、`aggregate_id`(Kafka のキー)は新旧の判定のキー(顧客 ID)。
 * - 出せる組(Canonical の型と届け先のトピック)は [supports] で確かめる(起動時に、すべての Flow の届け先を確かめる)。
 * - Canonical の検証を通らなければ NonRetryable(取り込みの側で DLQ。定期の同期では数えて先に進む)。
 */
public class OutboxCanonicalPublisher(
    private val database: Database,
    private val outbox: Outbox,
    private val events: OutboxEvents,
    private val customerChanged: AvroEventSerializer<CustomerChangedV1>,
) : CanonicalPublisher {
    @Suppress("ReturnCount") // 出せない組・トランザクションの外・結果のそれぞれで返す
    override suspend fun publish(
        flow: FlowDefinition,
        record: TransformedRecord,
        sourceSystem: String,
    ): Result<Unit, DomainError> {
        if (!supports(flow, record.deliver)) return err(UnexpectedError("${flow.canonical} を ${record.deliver} に出せません"))
        val transaction =
            database.currentTransaction()
                ?: return err(OutboxMisuse("Canonical のイベントは、新旧の判定の記録と同じトランザクションの中で書いてください"))
        val appended =
            CanonicalTypes
                .toCustomer(record.mapped)
                .flatMap { customer ->
                    val event = CustomerChangedV1(CustomerV1.of(customer), sourceSystem, record.version)
                    events.create(customerChanged, AGGREGATE_TYPE, record.key, event).mapError { it.asDomainError() }
                }.flatMap { outboxRecord -> outbox.appendOutbox(transaction, listOf(outboxRecord)).mapError { it.asDomainError() } }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える
        currentCoroutineContext().ensureActive()
        return appended
    }

    public companion object {
        private const val AGGREGATE_TYPE = "customer"

        /** [flow] の Canonical の型を、[topic] に出せるか(起動時に、すべての Flow の届け先を確かめる)。 */
        public fun supports(
            flow: FlowDefinition,
            topic: String,
        ): Boolean = flow.canonical == CanonicalTypes.CUSTOMER && topic == FlowEventSchemas.CUSTOMER_CHANGED.name
    }
}
