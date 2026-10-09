package io.eia.order.adapters.out.saga

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * Orchestrator が書くコマンドのトピックと契約のスキーマ(INT-INVENTORY/PAYMENT/SHIPPING-001。ADR-0029 §2)。
 * スキーマは contracts/avro のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。
 */
public object SagaSchemas {
    public val RESERVE_STOCK: EventTopic = EventTopic.of("inventory.stock.cmd-reserve.v1")
    public val RELEASE_STOCK: EventTopic = EventTopic.of("inventory.stock.cmd-release.v1")
    public val AUTHORIZE_PAYMENT: EventTopic = EventTopic.of("payment.payment.cmd-authorize.v1")
    public val VOID_PAYMENT: EventTopic = EventTopic.of("payment.payment.cmd-void.v1")
    public val ARRANGE_SHIPMENT: EventTopic = EventTopic.of("shipping.shipment.cmd-arrange.v1")
    public val CANCEL_SHIPMENT: EventTopic = EventTopic.of("shipping.shipment.cmd-cancel.v1")

    public val reserveStock: SchemaSubject by lazy { subject(RESERVE_STOCK, "inventory/ReserveStock") }
    public val releaseStock: SchemaSubject by lazy { subject(RELEASE_STOCK, "inventory/ReleaseStock") }
    public val authorizePayment: SchemaSubject by lazy { subject(AUTHORIZE_PAYMENT, "payment/AuthorizePayment") }
    public val voidPayment: SchemaSubject by lazy { subject(VOID_PAYMENT, "payment/VoidPayment") }
    public val arrangeShipment: SchemaSubject by lazy { subject(ARRANGE_SHIPMENT, "shipping/ArrangeShipment") }
    public val cancelShipment: SchemaSubject by lazy { subject(CANCEL_SHIPMENT, "shipping/CancelShipment") }

    /** 書くすべてのコマンドのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject>
        get() = listOf(reserveStock, releaseStock, authorizePayment, voidPayment, arrangeShipment, cancelShipment)

    /** コマンドの Serializer(起動時に解決した ID を使う)。 */
    public class Serializers(
        ids: SchemaIdBook,
    ) {
        public val reserve: AvroEventSerializer<ReserveStockV1> =
            AvroEventSerializer(RESERVE_STOCK, reserveStock, ReserveStockV1.serializer(), ids)
        public val release: AvroEventSerializer<ReleaseStockV1> =
            AvroEventSerializer(RELEASE_STOCK, releaseStock, ReleaseStockV1.serializer(), ids)
        public val authorize: AvroEventSerializer<AuthorizePaymentV1> =
            AvroEventSerializer(AUTHORIZE_PAYMENT, authorizePayment, AuthorizePaymentV1.serializer(), ids)
        public val void: AvroEventSerializer<VoidPaymentV1> =
            AvroEventSerializer(VOID_PAYMENT, voidPayment, VoidPaymentV1.serializer(), ids)
        public val arrange: AvroEventSerializer<ArrangeShipmentV1> =
            AvroEventSerializer(ARRANGE_SHIPMENT, arrangeShipment, ArrangeShipmentV1.serializer(), ids)
        public val cancel: AvroEventSerializer<CancelShipmentV1> =
            AvroEventSerializer(CANCEL_SHIPMENT, cancelShipment, CancelShipmentV1.serializer(), ids)
    }

    private fun subject(
        topic: EventTopic,
        record: String,
    ): SchemaSubject {
        val path = "/contracts/avro/$record.avsc"
        val schema = requireNotNull(SagaSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
        return SchemaSubject(topic.name, schema)
    }
}
