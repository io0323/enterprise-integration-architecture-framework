package io.eia.shipping.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * shipping-service が書く返事のイベントのトピックと契約のスキーマ(INT-SHIPPING-002。ADR-0025 §2・§3)。
 * スキーマは `contracts/avro/shipping` のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。
 */
public object ShippingEventSchemas {
    public val SHIPMENT_SHIPPED: EventTopic = EventTopic.of("shipping.shipment.shipped.v1")
    public val SHIPMENT_REJECTED: EventTopic = EventTopic.of("shipping.shipment.rejected.v1")
    public val SHIPMENT_CANCELLED: EventTopic = EventTopic.of("shipping.shipment.cancelled.v1")

    public val shipmentShipped: SchemaSubject by lazy { subject(SHIPMENT_SHIPPED, "ShipmentShipped") }
    public val shipmentRejected: SchemaSubject by lazy { subject(SHIPMENT_REJECTED, "ShipmentRejected") }
    public val shipmentCancelled: SchemaSubject by lazy { subject(SHIPMENT_CANCELLED, "ShipmentCancelled") }

    /** 書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(shipmentShipped, shipmentRejected, shipmentCancelled)

    /** 返事の Serializer(起動時に解決した ID を使う)。 */
    public class Serializers(
        ids: SchemaIdBook,
    ) {
        public val shipped: AvroEventSerializer<ShipmentShippedV1> =
            AvroEventSerializer(SHIPMENT_SHIPPED, shipmentShipped, ShipmentShippedV1.serializer(), ids)
        public val rejected: AvroEventSerializer<ShipmentRejectedV1> =
            AvroEventSerializer(SHIPMENT_REJECTED, shipmentRejected, ShipmentRejectedV1.serializer(), ids)
        public val cancelled: AvroEventSerializer<ShipmentCancelledV1> =
            AvroEventSerializer(SHIPMENT_CANCELLED, shipmentCancelled, ShipmentCancelledV1.serializer(), ids)
    }

    private fun subject(
        topic: EventTopic,
        record: String,
    ): SchemaSubject {
        val path = "/contracts/avro/shipping/$record.avsc"
        val schema =
            requireNotNull(ShippingEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
        return SchemaSubject(topic.name, schema)
    }
}
