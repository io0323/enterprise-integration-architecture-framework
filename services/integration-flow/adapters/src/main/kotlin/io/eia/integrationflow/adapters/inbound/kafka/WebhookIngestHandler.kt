package io.eia.integrationflow.adapters.inbound.kafka

import io.eia.integrationflow.adapters.FlowMetrics
import io.eia.integrationflow.adapters.flow.JsonRecords
import io.eia.integrationflow.adapters.out.persistence.TransientSqlError
import io.eia.integrationflow.application.port.inbound.IngestMessage
import io.eia.integrationflow.application.port.inbound.IngestOutcome
import io.eia.integrationflow.application.port.inbound.IngestWebhookUseCase
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.integrationflow.domain.Trigger
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventSubscription
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 社内の取り込みのトピック(WebhookReceived)の購読(ADR-0035 §1・ADR-0036 §4)。Consumer Group は [GROUP_ID] だけ。
 * 購読するトピックは、Flow の event の trigger のトピック(Flow の YAML を足せば、新しい取り込みのトピックも読む)。
 *
 * - 本文が UTF-8 の JSON のオブジェクトでなければ Rejected(DLQ。webhook-receiver は本文を検証しないで受け付けるため。ADR-0035 §3)。
 * - 冪等消費(platform/inbox)・新旧の判定・発行はユースケースが 1 つのトランザクションで行う。
 * - 写せない・Canonical の検証を通らない(NonRetryable)は Rejected(DLQ)、直列化の失敗などは Transient、DB に届かないなどは Unavailable。
 */
public class WebhookIngestHandler(
    private val catalog: FlowCatalog,
    private val ingest: IngestWebhookUseCase,
    private val metrics: FlowMetrics,
) {
    /** Flow が読む取り込みのトピック(連携 ID はそのトピックを読む最初の Flow の連携)。 */
    public val topics: Map<EventTopic, String> =
        catalog.flows
            .flatMap { flow -> flow.triggers.filterIsInstance<Trigger.Event>().map { EventTopic.of(it.topic) to flow.integration } }
            .distinctBy { it.first.name }
            .toMap()

    public fun subscriptions(writerSchemas: WriterSchemas): List<EventSubscription<*>> =
        topics.map { (topic, integration) ->
            EventSubscription(topic, AvroEventDeserializer(WebhookReceivedV1.serializer(), writerSchemas), integration, ::handle)
        }

    internal suspend fun handle(event: ConsumedEvent<WebhookReceivedV1>): Result<Handled, HandlingFailure> {
        val body =
            JsonRecords.parse(event.value.body)
                ?: return err(HandlingFailure.Rejected("webhook_body_invalid", "本文が UTF-8 の JSON のオブジェクトではありません")).also {
                    metrics.record(FlowMetrics.NONE, FlowMetrics.WEBHOOK, FlowMetrics.FAILED)
                }
        val message = IngestMessage(event.metadata.id.toString(), event.topic, event.value.source, body)
        return when (val outcome = ingest(message)) {
            is Result.Ok -> {
                ok(count(outcome.value))
            }

            is Result.Err -> {
                if (outcome.error is DomainError.NonRetryable) metrics.record(FlowMetrics.NONE, FlowMetrics.WEBHOOK, FlowMetrics.FAILED)
                err(failure(outcome.error))
            }
        }
    }

    private fun count(outcome: IngestOutcome): Handled =
        when (outcome) {
            IngestOutcome.Duplicate -> {
                metrics.record(FlowMetrics.NONE, FlowMetrics.WEBHOOK, FlowMetrics.DUPLICATE)
                Handled.DUPLICATE
            }

            is IngestOutcome.Ignored -> {
                metrics.record(FlowMetrics.NONE, FlowMetrics.WEBHOOK, FlowMetrics.IGNORED)
                Handled.PROCESSED
            }

            is IngestOutcome.Processed -> {
                outcome.decisions.forEach { (flow, decision) -> metrics.decision(flow, FlowMetrics.WEBHOOK, decision) }
                Handled.PROCESSED
            }
        }

    private fun failure(error: DomainError): HandlingFailure =
        when (error) {
            is TransientSqlError -> HandlingFailure.Transient(error.code, error.message)
            is DomainError.Retryable -> HandlingFailure.Unavailable(error.code, error.message)
            is DomainError.NonRetryable -> HandlingFailure.Rejected(error.code, error.message)
        }

    public companion object {
        /** カタログ(INT-INTEGRATION-001)の consumer group。 */
        public const val GROUP_ID: String = "integration-flow.webhook-ingest"
    }
}
