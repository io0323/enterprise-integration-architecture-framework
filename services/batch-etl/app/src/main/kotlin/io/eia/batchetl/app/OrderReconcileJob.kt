package io.eia.batchetl.app

import io.eia.batchetl.application.port.inbound.ReconcileOrderMartUseCase
import io.eia.batchetl.application.port.inbound.ReconcileReport
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.ReconcileOutcome
import io.eia.platform.batch.BatchJob
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.Schedule
import io.eia.platform.batch.Trigger
import io.eia.platform.batch.dag
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.onOk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

/**
 * 週次の Full 照合の DAG `batch-etl.order-reconcile`(ADR-0031 §6)。パーティションは実行の日(日本時間の日付)。
 * 毎週 [weeklyOn] の [at](日本時間)に起動する(日次の DAG の SLA の期限の後)。照合の中で今の末尾まで取り込んでから比べる。
 */
internal object OrderReconcileJob {
    const val DAG_ID: String = "batch-etl.order-reconcile"
    private val TOKYO = TimeZone.of(BusinessDay.TIME_ZONE_ID)

    fun dag(
        reconcile: ReconcileOrderMartUseCase,
        metrics: ReconcileMetrics,
    ): DagDefinition =
        dag(DAG_ID) {
            // 注文の日次の集計(INT-SALES-001)
            integrationId = "INT-SALES-001"
            step("reconcile") {
                // 38 日分の問い合わせと、比べ直しの取り込み
                expected = 10.minutes
                action { context ->
                    BusinessDay
                        .parse(context.run.partition.value, "partition")
                        .flatMap { day -> reconcile(day).onOk(metrics::reconciled) }
                        .map { }
                }
            }
        }

    fun job(
        dag: DagDefinition,
        weeklyOn: DayOfWeek,
        at: LocalTime,
    ): BatchJob = BatchJob(dag, Trigger.Scheduled(Schedule.weekly(weeklyOn, at, TOKYO, partitionDaysBefore = 0)))
}

/**
 * 照合のメトリクスと、直近の結果(`reconcile` コマンドの終了コードに使う)。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.batch_etl.reconcile.days` | counter | `batch_etl.reconcile.outcome`(`match` / `drift` / `unverified`) |
 * | `eia.batch_etl.reconcile.drift_days` | gauge | なし。直近の照合で、比べ直しても一致しなかった日の数(0 より大きければアラート。P08 ⑤) |
 */
internal class ReconcileMetrics(
    meter: Meter,
) {
    private val days: LongCounter =
        meter
            .counterBuilder("eia.batch_etl.reconcile.days")
            .setUnit("{day}")
            .setDescription("照合した日の数(結果ごと)")
            .build()
    private val last = AtomicReference<ReconcileReport?>()

    init {
        meter
            .gaugeBuilder("eia.batch_etl.reconcile.drift_days")
            .ofLongs()
            .setUnit("{day}")
            .setDescription("直近の照合で、比べ直しても一致しなかった日の数")
            .buildWithCallback { measurement -> last.get()?.let { measurement.record(it.drift().size.toLong()) } }
    }

    val lastReport: ReconcileReport? get() = last.get()

    fun reconciled(report: ReconcileReport) {
        report.days.groupingBy { it.outcome }.eachCount().forEach { (outcome, count) ->
            days.add(count.toLong(), Attributes.of(OUTCOME, outcome.name.lowercase()))
        }
        last.set(report)
        val drift = report.drift()
        if (drift.isNotEmpty()) {
            // 運用者が調べる(docs/runbooks/batch-rerun.md)。件数とハッシュだけを出す(注文の値は出さない)
            logger.warn("照合で一致しない日があります(実行の日 {}): {}", report.runDay, drift.joinToString())
        }
    }

    private fun ReconcileReport.drift(): List<BusinessDay> = days.filter { it.outcome == ReconcileOutcome.DRIFT }.map { it.day }

    private companion object {
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("batch_etl.reconcile.outcome")
        val logger = LoggerFactory.getLogger(ReconcileMetrics::class.java)
    }
}
