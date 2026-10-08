package io.eia.legacyorderacl.adapters.outbound

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * legacy-order-acl が書くイベントのトピックと契約のスキーマ(ADR-0025 §2・§3。ADR-0026 §9)。
 * スキーマは `contracts/avro` のファイルを、ビルド時にリソースへコピーしたもの。起動時に [subjects] の ID を解決する。
 */
public object LegacyOrderEventSchemas {
    public val LEGACY_ORDER_CHANGED: EventTopic = EventTopic.of("sales.legacy-order.changed.v1")
    private const val LEGACY_ORDER_CHANGED_SCHEMA = "/contracts/avro/sales/LegacyOrderChanged.avsc"

    public val legacyOrderChanged: SchemaSubject by lazy { SchemaSubject(LEGACY_ORDER_CHANGED.name, resource(LEGACY_ORDER_CHANGED_SCHEMA)) }

    /** legacy-order-acl が書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(legacyOrderChanged)

    internal fun legacyOrderChangedSerializer(ids: SchemaIdBook): AvroEventSerializer<LegacyOrderChangedV1> =
        AvroEventSerializer(LEGACY_ORDER_CHANGED, legacyOrderChanged, LegacyOrderChangedV1.serializer(), ids)

    private fun resource(path: String): String =
        requireNotNull(LegacyOrderEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
}
