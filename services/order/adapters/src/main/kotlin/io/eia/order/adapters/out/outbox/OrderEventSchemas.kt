package io.eia.order.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * order-service が書くイベントのトピックと契約のスキーマ(ADR-0025 §2・§3)。
 *
 * スキーマは `contracts/avro` のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。契約が唯一の真実で、
 * コードに複製しない。起動時に [subjects] の ID を `SchemaIdBook` で解決し、リクエストの処理中はレジストリに問い合わせない。
 */
public object OrderEventSchemas {
    public val ORDER_CREATED: EventTopic = EventTopic.of("sales.order.created.v1")
    private const val ORDER_CREATED_SCHEMA = "/contracts/avro/sales/OrderCreated.avsc"

    public val orderCreated: SchemaSubject by lazy { SchemaSubject(ORDER_CREATED.name, resource(ORDER_CREATED_SCHEMA)) }

    public val ORDER_CANCELLED: EventTopic = EventTopic.of("sales.order.cancelled.v1")
    private const val ORDER_CANCELLED_SCHEMA = "/contracts/avro/sales/OrderCancelled.avsc"
    public val orderCancelled: SchemaSubject by lazy { SchemaSubject(ORDER_CANCELLED.name, resource(ORDER_CANCELLED_SCHEMA)) }

    public val ORDER_STATUS_CHANGED: EventTopic = EventTopic.of("sales.order.status-changed.v1")
    private const val ORDER_STATUS_CHANGED_SCHEMA = "/contracts/avro/sales/OrderStatusChanged.avsc"
    public val orderStatusChanged: SchemaSubject by lazy {
        SchemaSubject(ORDER_STATUS_CHANGED.name, resource(ORDER_STATUS_CHANGED_SCHEMA))
    }

    /** order-service が書く注文のイベントのスキーマ(起動時に ID を解決する対象。Saga のコマンドは `SagaSchemas`)。 */
    public val subjects: List<SchemaSubject> get() = listOf(orderCreated, orderCancelled, orderStatusChanged)

    public fun orderCreatedSerializer(ids: SchemaIdBook): AvroEventSerializer<OrderCreatedV1> =
        AvroEventSerializer(ORDER_CREATED, orderCreated, OrderCreatedV1.serializer(), ids)

    public fun orderCancelledSerializer(ids: SchemaIdBook): AvroEventSerializer<OrderCancelledV1> =
        AvroEventSerializer(ORDER_CANCELLED, orderCancelled, OrderCancelledV1.serializer(), ids)

    public fun orderStatusChangedSerializer(ids: SchemaIdBook): AvroEventSerializer<OrderStatusChangedV1> =
        AvroEventSerializer(ORDER_STATUS_CHANGED, orderStatusChanged, OrderStatusChangedV1.serializer(), ids)

    /** [OutboxOrderEvents] が書く全部のイベントの Serializer。 */
    public fun serializers(ids: SchemaIdBook): OutboxOrderEvents.Serializers =
        OutboxOrderEvents.Serializers(orderCreatedSerializer(ids), orderCancelledSerializer(ids), orderStatusChangedSerializer(ids))

    private fun resource(path: String): String =
        requireNotNull(OrderEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
}
