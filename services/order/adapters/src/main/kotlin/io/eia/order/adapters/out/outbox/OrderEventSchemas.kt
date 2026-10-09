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

    /** order-service が書く注文のイベントのスキーマ(起動時に ID を解決する対象。Saga のコマンドは `SagaSchemas`)。 */
    public val subjects: List<SchemaSubject> get() = listOf(orderCreated, orderCancelled)

    public fun orderCreatedSerializer(ids: SchemaIdBook): AvroEventSerializer<OrderCreatedV1> =
        AvroEventSerializer(ORDER_CREATED, orderCreated, OrderCreatedV1.serializer(), ids)

    public fun orderCancelledSerializer(ids: SchemaIdBook): AvroEventSerializer<OrderCancelledV1> =
        AvroEventSerializer(ORDER_CANCELLED, orderCancelled, OrderCancelledV1.serializer(), ids)

    private fun resource(path: String): String =
        requireNotNull(OrderEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
}
