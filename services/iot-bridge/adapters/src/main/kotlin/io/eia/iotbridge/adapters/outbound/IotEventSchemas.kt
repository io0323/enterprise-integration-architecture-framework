package io.eia.iotbridge.adapters.outbound

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * iot-bridge が書くイベントのトピックと契約のスキーマ(ADR-0025 §2・§3。ADR-0039)。
 * スキーマは `contracts/avro` のファイルを、ビルド時にリソースへコピーしたもの。起動時に [subjects] の ID を解決する。
 */
public object IotEventSchemas {
    public val TELEMETRY_REPORTED: EventTopic = EventTopic.of("iot.telemetry.reported.v1")
    private const val TELEMETRY_REPORTED_SCHEMA = "/contracts/avro/iot/TelemetryReported.avsc"

    public val telemetryReported: SchemaSubject by lazy { SchemaSubject(TELEMETRY_REPORTED.name, resource(TELEMETRY_REPORTED_SCHEMA)) }

    /** iot-bridge が書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(telemetryReported)

    internal fun telemetryReportedSerializer(ids: SchemaIdBook): AvroEventSerializer<TelemetryReportedV1> =
        AvroEventSerializer(TELEMETRY_REPORTED, telemetryReported, TelemetryReportedV1.serializer(), ids)

    private fun resource(path: String): String =
        requireNotNull(IotEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
}
