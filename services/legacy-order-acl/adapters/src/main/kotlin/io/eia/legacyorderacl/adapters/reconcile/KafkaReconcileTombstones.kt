package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.application.port.outbound.ReconcileTombstones
import io.eia.platform.messagingkafka.EventProducer
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/**
 * 照合が出力に書く tombstone(ADR-0027 §6)。`ce_source` を [SOURCE] にし、変換が書く削除(`/sales/legacy-order-acl`)と見分けられるようにする。
 * [producer] は、[SOURCE] で作った `EventProducer`(Kafka の Producer は変換と共有してよい)。
 */
public class KafkaReconcileTombstones(
    private val producer: EventProducer,
) : ReconcileTombstones {
    override suspend fun delete(orderNumber: String): Result<Unit, DomainError> =
        producer
            .sendTombstone(LegacyOrderEventSchemas.LEGACY_ORDER_CHANGED, orderNumber)
            .mapError { it.asDomainError() }
            .map { }

    public companion object {
        /** 照合が書いた削除の `ce_source`。 */
        public const val SOURCE: String = "/sales/legacy-order-acl/reconcile"
    }
}
