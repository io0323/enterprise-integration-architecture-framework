package io.eia.integrationflow.application.port.inbound

import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 社内の取り込みのトピックの Webhook を 1 件処理する(ADR-0036)。冪等消費の記録・新旧の判定の記録・発行を 1 つのトランザクションで行う。
 * 写せない・Canonical の検証を通らないときは NonRetryable の誤り(取り込みの側で DLQ に隔離する)。
 */
public fun interface IngestWebhookUseCase {
    public suspend operator fun invoke(message: IngestMessage): Result<IngestOutcome, DomainError>
}

/**
 * @property messageId CloudEvents の `ce_id`(冪等消費のキー)
 * @property source Webhook の送り手(WebhookReceived の `source`)
 * @property body Webhook の本文(JSON を読んだもの)
 */
public class IngestMessage(
    public val messageId: String,
    public val topic: String,
    public val source: String,
    public val body: SourceValue,
) {
    override fun toString(): String = "IngestMessage(messageId=$messageId, topic=$topic, source=$source)"
}

public sealed interface IngestOutcome {
    /** 処理済みの `ce_id`(Kafka の再送・DLQ の Replay)。何もしない。 */
    public data object Duplicate : IngestOutcome

    /** どの Flow も取り込まない種類(例 `customer.deleted`)。記録だけして何も出さない。 */
    public data class Ignored(
        val eventType: String,
    ) : IngestOutcome

    /** 取り込んだ。Flow ごとの新旧の判定(NEWER のものだけを出した)。 */
    public data class Processed(
        val decisions: List<Pair<String, VersionDecision>>,
    ) : IngestOutcome
}
