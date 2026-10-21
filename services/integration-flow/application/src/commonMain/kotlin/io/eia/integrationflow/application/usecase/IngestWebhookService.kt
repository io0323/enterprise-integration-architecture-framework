package io.eia.integrationflow.application.usecase

import io.eia.integrationflow.application.port.inbound.IngestMessage
import io.eia.integrationflow.application.port.inbound.IngestOutcome
import io.eia.integrationflow.application.port.inbound.IngestWebhookUseCase
import io.eia.integrationflow.application.port.inbound.TransformRecordUseCase
import io.eia.integrationflow.application.port.outbound.ProcessedMessages
import io.eia.integrationflow.application.port.outbound.TransactionRunner
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.FlowMappingError
import io.eia.integrationflow.domain.SourcePath
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.Trigger
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * [IngestWebhookUseCase] の実装。1 つのトランザクションで、冪等消費の記録 → Flow の選択 → 写す → 新旧の判定 → 新しい版だけ発行。
 * 失敗すれば冪等消費の記録も取り消す(DLQ・読み直しで、もう一度処理できる)。
 */
public class IngestWebhookService(
    private val catalog: FlowCatalog,
    private val transactions: TransactionRunner,
    private val processed: ProcessedMessages,
    private val publisher: RecordPublisher,
    private val transform: TransformRecordUseCase = TransformRecordService(),
) : IngestWebhookUseCase {
    override suspend fun invoke(message: IngestMessage): Result<IngestOutcome, DomainError> =
        transactions.inTransaction {
            when (val first = processed.markProcessed(message.messageId, message.topic)) {
                is Result.Err -> first
                is Result.Ok -> if (first.value) ingest(message) else ok(IngestOutcome.Duplicate)
            }
        }

    @Suppress("ReturnCount") // 種類がない・取り込む Flow がない・Flow ごとの失敗のそれぞれで返す
    private suspend fun ingest(message: IngestMessage): Result<IngestOutcome, DomainError> {
        val eventType =
            (TYPE.read(message.body) as? SourceValue.Text)?.value
                ?: return err(FlowMappingError(TYPE.text, "Webhook の種類(type)がありません"))
        val flows = catalog.forEvent(message.topic, message.source, eventType)
        if (flows.isEmpty()) return ok(IngestOutcome.Ignored(eventType))
        val decisions = mutableListOf<Pair<String, VersionDecision>>()
        for ((flow, trigger) in flows) {
            when (val decided = ingest(flow, trigger, message)) {
                is Result.Err -> return decided
                is Result.Ok -> decisions += flow.id to decided.value
            }
        }
        return ok(IngestOutcome.Processed(decisions))
    }

    @Suppress("ReturnCount") // 元のレコードがない・写せないのそれぞれで返す
    private suspend fun ingest(
        flow: FlowDefinition,
        trigger: Trigger.Event,
        message: IngestMessage,
    ): Result<VersionDecision, DomainError> {
        val record = trigger.record.read(message.body) ?: return err(FlowMappingError(trigger.record.text, "元のレコードがありません"))
        val transformed =
            when (val result = transform(flow, record)) {
                is Result.Err -> return result
                is Result.Ok -> result.value
            }
        return publisher.publishIfNewer(flow, transformed, message.source)
    }

    private companion object {
        val TYPE: SourcePath = checkNotNull(SourcePath.parse("$.type"))
    }
}
