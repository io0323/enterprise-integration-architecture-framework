package io.eia.integrationflow.application.usecase

import io.eia.integrationflow.application.port.inbound.SyncFlowUseCase
import io.eia.integrationflow.application.port.inbound.SyncReport
import io.eia.integrationflow.application.port.inbound.TransformRecordUseCase
import io.eia.integrationflow.application.port.outbound.SaasConnector
import io.eia.integrationflow.application.port.outbound.SyncWatermarks
import io.eia.integrationflow.application.port.outbound.TransactionRunner
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.Trigger
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * [SyncFlowUseCase] の実装。
 *
 * - 前の同期の Watermark([SyncWatermarks]。なければ全件)の後に変わったレコードを、ページの終わりまで読む。
 * - レコードごとに 1 つのトランザクションで、新旧の記録を進めて新しい版だけを出す(Webhook の経路と同じ記録。ADR-0035 §4)。
 * - すべてのレコードを処理できたら、Watermark を **この同期を始めた時刻から [overlap] を引いた時刻** に進める
 *   (SaaS と自分の時計のずれと、同期の間に変わったレコードを取りこぼさないため。重なって読んだレコードは同じ版として出さない)。
 * - 写せないレコードがあれば、数えて先に進むが、Watermark は進めない(次の回にもう一度読む。新旧の記録があるので、出し直さない)。
 * - Connector・DB の失敗は、そこで止めて Err(Watermark は進めない)。
 */
public class SyncFlowService(
    private val connectors: Map<String, SaasConnector>,
    private val transactions: TransactionRunner,
    private val publisher: RecordPublisher,
    private val watermarks: SyncWatermarks,
    private val clock: Clock = Clock.System,
    private val overlap: Duration = DEFAULT_OVERLAP,
) : SyncFlowUseCase {
    private val transform: TransformRecordUseCase = TransformRecordService()

    @Suppress("ReturnCount") // Connector・Watermark・DB の失敗のそれぞれで止める(Watermark を進めない)
    override suspend fun invoke(
        flow: FlowDefinition,
        trigger: Trigger.Poll,
    ): Result<SyncReport, DomainError> {
        val connector = connectors[trigger.connector] ?: return err(UnexpectedError("Connector ${trigger.connector} がありません"))
        val since =
            when (val read = watermarks.get(flow.id, trigger.connector)) {
                is Result.Err -> return read
                is Result.Ok -> read.value
            }
        val startedAt = clock.now()
        val counts = Counts()
        var cursor: String? = null
        do {
            val page =
                when (val read = connector.page(trigger.resource, since, cursor)) {
                    is Result.Err -> return read
                    is Result.Ok -> read.value
                }
            counts.pages++
            for (record in page.records) {
                val failure = sync(flow, connector, record, counts)
                if (failure != null) return err(failure)
            }
            cursor = page.nextCursor
        } while (cursor != null)
        if (counts.failed == 0) {
            when (val saved = watermarks.set(flow.id, trigger.connector, startedAt - overlap)) {
                is Result.Err -> return saved
                is Result.Ok -> Unit
            }
        }
        return ok(counts.report())
    }

    /** 1 件を処理する。止めるべき失敗(Retryable)なら返す。写せないレコードは数えて null。 */
    private suspend fun sync(
        flow: FlowDefinition,
        connector: SaasConnector,
        record: SourceValue,
        counts: Counts,
    ): DomainError? {
        val transformed =
            when (val result = transform(flow, record)) {
                is Result.Err -> return null.also { counts.failed++ }
                is Result.Ok -> result.value
            }
        return when (
            val decided =
                transactions.inTransaction { publisher.publishIfNewer(flow, transformed, connector.sourceSystem) }
        ) {
            is Result.Ok -> {
                counts.count(decided.value)
                null
            }

            is Result.Err -> {
                if (decided.error is DomainError.Retryable) decided.error else null.also { counts.failed++ }
            }
        }
    }

    private class Counts {
        var pages = 0
        var published = 0
        var same = 0
        var stale = 0
        var failed = 0

        fun count(decision: VersionDecision) {
            when (decision) {
                VersionDecision.NEWER -> published++
                VersionDecision.SAME -> same++
                VersionDecision.STALE -> stale++
            }
        }

        fun report() = SyncReport(pages, published, same, stale, failed)
    }

    public companion object {
        public val DEFAULT_OVERLAP: Duration = 5.minutes
    }
}
