package io.eia.webhookreceiver.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * webhook-receiver が書く取り込みのイベントのトピックと契約のスキーマ(INT-INTEGRATION-001。ADR-0025 §2・§3)。
 * スキーマは `contracts/avro/integration` のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。
 */
public object WebhookIngestSchemas {
    public val CRM_WEBHOOK_RECEIVED: EventTopic = EventTopic.of("integration.crm-webhook.received.v1")
    private const val SCHEMA = "/contracts/avro/integration/WebhookReceived.avsc"

    public val webhookReceived: SchemaSubject by lazy {
        val schema = requireNotNull(WebhookIngestSchemas::class.java.getResource(SCHEMA)) { "$SCHEMA がリソースにありません(contracts からのコピー)" }
        SchemaSubject(CRM_WEBHOOK_RECEIVED.name, schema.readText())
    }

    /** 書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(webhookReceived)

    public fun serializer(ids: SchemaIdBook): AvroEventSerializer<WebhookReceivedV1> =
        AvroEventSerializer(CRM_WEBHOOK_RECEIVED, webhookReceived, WebhookReceivedV1.serializer(), ids)
}
