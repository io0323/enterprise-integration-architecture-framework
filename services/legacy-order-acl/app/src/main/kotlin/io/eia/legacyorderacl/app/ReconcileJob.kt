package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.reconcile.ReconcileMetrics
import io.eia.legacyorderacl.application.port.inbound.ReconcileLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
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
) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            runOnce()
            delay(interval)
        }
    }

    suspend fun runOnce(): Result<ReconciliationReport, DomainError> =
        withContext(ObservabilityContext(CorrelationId.generate(), INTEGRATION_ID, Context.root())) {
            observability.withSpan("legacy-order reconcile") {
                reconcile().also { result ->
                    when (result) {
                        is Result.Ok -> {
                            recordSuccess(result.value)
                        }

                        is Result.Err -> {
                            metrics.failed(result.error.code)
                            logger.warn("照合を終えられませんでした(ずれではなく検査の失敗): {}", result.error.message)
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
