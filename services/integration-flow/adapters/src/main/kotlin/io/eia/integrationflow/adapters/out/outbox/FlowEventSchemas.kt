package io.eia.integrationflow.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * integration-flow が書く Canonical のイベントのトピックと契約のスキーマ(ADR-0025 §2・§3)。
 * スキーマは `contracts/avro` のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。
 */
public object FlowEventSchemas {
    public val CUSTOMER_CHANGED: EventTopic = EventTopic.of("sales.customer.changed.v1")

    public val customerChanged: SchemaSubject by lazy {
        SchemaSubject(CUSTOMER_CHANGED.name, resource("/contracts/avro/sales/CustomerChanged.avsc"))
    }

    /** 書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(customerChanged)

    public fun customerChangedSerializer(ids: SchemaIdBook): AvroEventSerializer<CustomerChangedV1> =
        AvroEventSerializer(CUSTOMER_CHANGED, customerChanged, CustomerChangedV1.serializer(), ids)

    internal fun resource(path: String): String =
        requireNotNull(FlowEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
}
