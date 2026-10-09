package io.eia.inventory.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * inventory-service が書く返事のイベントのトピックと契約のスキーマ(INT-INVENTORY-002。ADR-0025 §2・§3)。
 * スキーマは `contracts/avro/inventory` のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。
 */
public object InventoryEventSchemas {
    public val STOCK_RESERVED: EventTopic = EventTopic.of("inventory.stock.reserved.v1")
    public val STOCK_RESERVATION_REJECTED: EventTopic = EventTopic.of("inventory.stock.reservation-rejected.v1")
    public val STOCK_RELEASED: EventTopic = EventTopic.of("inventory.stock.released.v1")

    public val stockReserved: SchemaSubject by lazy { subject(STOCK_RESERVED, "StockReserved") }
    public val stockReservationRejected: SchemaSubject by lazy { subject(STOCK_RESERVATION_REJECTED, "StockReservationRejected") }
    public val stockReleased: SchemaSubject by lazy { subject(STOCK_RELEASED, "StockReleased") }

    /** 書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(stockReserved, stockReservationRejected, stockReleased)

    /** 返事の Serializer(起動時に解決した ID を使う)。 */
    public class Serializers(
        ids: SchemaIdBook,
    ) {
        public val reserved: AvroEventSerializer<StockReservedV1> =
            AvroEventSerializer(STOCK_RESERVED, stockReserved, StockReservedV1.serializer(), ids)
        public val rejected: AvroEventSerializer<StockReservationRejectedV1> =
            AvroEventSerializer(STOCK_RESERVATION_REJECTED, stockReservationRejected, StockReservationRejectedV1.serializer(), ids)
        public val released: AvroEventSerializer<StockReleasedV1> =
            AvroEventSerializer(STOCK_RELEASED, stockReleased, StockReleasedV1.serializer(), ids)
    }

    private fun subject(
        topic: EventTopic,
        record: String,
    ): SchemaSubject {
        val path = "/contracts/avro/inventory/$record.avsc"
        val schema =
            requireNotNull(InventoryEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }
                .readText()
        return SchemaSubject(topic.name, schema)
    }
}
