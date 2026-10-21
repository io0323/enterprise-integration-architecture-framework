package io.eia.integrationflow.application.usecase

import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.application.port.outbound.CanonicalPublisher
import io.eia.integrationflow.application.port.outbound.FlowVersions
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 新旧の記録を進め、新しい版だけを発行する。**Webhook の経路と定期の同期の経路が同じこの部品(同じ記録)を使う**(ADR-0035 §4・ADR-0036 §3)。
 * 呼び出し側のトランザクションの中で呼ぶ(記録と発行が一緒に確定する)。
 */
public class RecordPublisher(
    private val versions: FlowVersions,
    private val publisher: CanonicalPublisher,
) {
    public suspend fun publishIfNewer(
        flow: FlowDefinition,
        record: TransformedRecord,
        sourceSystem: String,
    ): Result<VersionDecision, DomainError> =
        when (val decision = versions.advance(flow.id, record.key, record.version)) {
            is Result.Err -> {
                decision
            }

            is Result.Ok -> {
                if (decision.value == VersionDecision.NEWER) {
                    when (val published = publisher.publish(flow, record, sourceSystem)) {
                        is Result.Err -> published
                        is Result.Ok -> decision
                    }
                } else {
                    decision
                }
            }
        }
}
