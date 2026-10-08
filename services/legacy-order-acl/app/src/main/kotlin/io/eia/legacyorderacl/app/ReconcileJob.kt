package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.reconcile.ReconcileMetrics
import io.eia.legacyorderacl.application.port.inbound.ReconcileLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.legacyorderacl.application.port.inbound.ResyncLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.ResyncResult
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.opentelemetry.context.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * 照合の定期実行(ADR-0027)。起動の直後と [interval] ごとに 1 回照合し、結果をメトリクスとログに残す。
 * ずれのキー(注文番号)はログに出す(部分の再同期の対象。注文番号は業務の識別子で、顧客の値は出さない)。
 */
internal class ReconcileJob(
    private val reconcile: ReconcileLegacyOrdersUseCase,
    private val metrics: ReconcileMetrics,
    private val observability: ObservabilityRuntime,
    private val interval: Duration,
    private val resync: ResyncLegacyOrdersUseCase? = null,
) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            runOnce()
            delay(interval)
        }
    }

    /** 1 回照合し、[resync] が真で自動の再同期が有効なら、ずれを取り直す。 */
    suspend fun runOnce(resync: Boolean = true): Result<ReconciliationReport, DomainError> =
        withContext(ObservabilityContext(CorrelationId.generate(), INTEGRATION_ID, Context.root())) {
            observability.withSpan("legacy-order reconcile") {
                reconcile().also { result ->
                    when (result) {
                        is Result.Ok -> {
                            recordSuccess(result.value)
                            if (!result.value.consistent) resyncDrift(result.value, resync)
                        }

                        is Result.Err -> {
                            metrics.failed(result.error.code)
                            logger.warn("照合を終えられませんでした(ずれではなく検査の失敗): {}", result.error.message)
                        }
                    }
                }
            }
        }

    /** ずれを取り直す(ADR-0027 §6)。上限を超えたら何もせず、アラート(LegacyReconcileDriftOverLimit)に任せる。 */
    private suspend fun resyncDrift(
        report: ReconciliationReport,
        enabled: Boolean,
    ) {
        val useCase = resync
        if (useCase == null || !enabled) {
            metrics.resynced("disabled")
            return
        }
        when (val result = useCase(report)) {
            is Result.Err -> {
                metrics.resynced("error")
                logger.warn("再同期に失敗しました(次の照合で、もう一度ずれとして見つかる): {}", result.error.message)
            }

            is Result.Ok -> {
                when (val done = result.value) {
                    is ResyncResult.NothingToDo -> {
                        Unit
                    }

                    is ResyncResult.OverLimit -> {
                        metrics.resynced("over_limit")
                        logger.error("ずれ {} 件が再同期の上限 {} 件を超えたため、取り直しません(人が判断する。cdc-resync.md)", done.driftKeys, done.limit)
                    }

                    is ResyncResult.Requested -> {
                        metrics.resynced("requested", snapshot = done.snapshotRequested.size, tombstone = done.tombstoned.size)
                        logger.info(
                            "再同期: Snapshot を指示 {} 件、tombstone {} 件、確かめ直したらあった {} 件",
                            done.snapshotRequested.size,
                            done.tombstoned.size,
                            done.reappeared.size,
                        )
                    }
                }
            }
        }
    }

    private fun recordSuccess(report: ReconciliationReport) {
        metrics.succeeded(report)
        if (report.consistent) {
            logger.info(
                "照合: 一致(比べたキー {}、変換できないキー {}、位置 {}、全体の SHA-256 {})",
                report.compared,
                report.unconvertible.size,
                report.position,
                report.digest,
            )
        } else {
            val byKind = report.drift.entries.groupBy({ it.value }, { it.key })
            logger.warn(
                "照合: ずれ {} 件 {}(位置 {}、変換できないキー {})",
                report.drift.size,
                byKind.mapValues { (_, keys) -> keys.take(LOGGED_KEYS) },
                report.position,
                report.unconvertible.size,
            )
        }
    }

    private companion object {
        const val INTEGRATION_ID = "INT-SALES-003"

        /** ログに出す、種類ごとのずれのキーの数の上限(全件はメトリクスと reconcile のサブコマンドで見る)。 */
        const val LOGGED_KEYS = 20
        val logger = LoggerFactory.getLogger(ReconcileJob::class.java)
    }
}
