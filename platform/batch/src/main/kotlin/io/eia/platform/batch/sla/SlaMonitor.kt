package io.eia.platform.batch.sla

import io.eia.platform.batch.BatchError
import io.eia.platform.batch.BatchJob
import io.eia.platform.batch.BatchMetrics
import io.eia.platform.batch.BatchStore
import io.eia.platform.batch.DagId
import io.eia.platform.batch.RunKey
import io.eia.platform.batch.RunStatus
import io.eia.platform.batch.Trigger
import io.eia.platform.batch.upstreamFirst
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.Instant

/**
 * 実行の完了の予測と期限。
 *
 * @property waitingFor まだ始まっていない実行が待っている前段の DAG。始まっている実行では null
 */
public data class SlaPrediction(
    val run: RunKey,
    val predictedEnd: Instant,
    val deadline: Instant,
    val waitingFor: DagId?,
) {
    /** 期限までの余裕。負なら遅れる見込み。 */
    val slack: Duration get() = deadline - predictedEnd
}

/**
 * SLA の遅延の予測(Framework 7.2: 遅れると予測した時点で知らせる。ADR-0030 §6)。[evaluate] のたびに予測を作り直し、
 * DAG ごとの余裕の最小値をメトリクス `eia.batch.sla.slack`(秒。属性 `batch.dag`)に出す。負になったらアラートにする(P08 ⑤)。
 *
 * 予測する実行:
 * - 実行中(RUNNING)と、失敗して再実行を待っている(FAILED)実行。止まった実行も、時間がたつほど予測が後ろにずれる。
 * - 前段の DAG([Trigger.AfterDag])の実行を待っている、まだ始まっていない実行。前段の完了の予測を開始の見込みにする
 *   ([SlaPredictor] の earliestStart)。前段が遅れれば、後段は始まる前から遅れる見込みになる。前段の DAG も [jobs] に入れる。
 *
 * 期限を過ぎた実行は余裕の最小値に入れない(終わったときに `eia.batch.sla.missed` で数える)。
 */
public class SlaMonitor(
    jobs: List<BatchJob>,
    private val store: BatchStore,
    private val clock: Clock = Clock.System,
    meter: Meter? = null,
    private val lookback: Duration = 7.days,
) {
    private val jobs: List<BatchJob> = upstreamFirst(jobs)
    private val slack = AtomicReference<Map<DagId, Double>>(emptyMap())
    private val warned = ConcurrentHashMap.newKeySet<RunKey>()

    init {
        meter
            ?.gaugeBuilder("eia.batch.sla.slack")
            ?.setUnit("s")
            ?.setDescription("SLA の期限までの余裕の予測(DAG の実行のうちの最小。負なら遅れる見込み)")
            ?.buildWithCallback { measurement ->
                slack.get().forEach { (dag, seconds) -> measurement.record(seconds, Attributes.of(BatchMetrics.DAG, dag.value)) }
            }
    }

    /** 予測を作り直す。期限のある実行の予測を返す。 */
    @Suppress("ReturnCount") // 記録を読めないときは、そこで返す
    public fun evaluate(): Result<List<SlaPrediction>, BatchError> {
        val round = Round(clock.now())
        for (job in jobs) {
            val estimates =
                when (val loaded = StepEstimates.of(job.dag, store)) {
                    is Result.Err -> return loaded
                    is Result.Ok -> loaded.value
                }
            (predictActive(job, estimates, round) ?: predictWaiting(job, estimates, round))?.let { return err(it) }
        }
        publish(round.predictions, round.now)
        return ok(round.predictions)
    }

    /** 1 回の評価の途中の結果。[predicted] は期限のない DAG も含む完了の予測(後段の開始の見込みに使う)。 */
    private class Round(
        val now: Instant,
    ) {
        val predicted = mutableMapOf<RunKey, Instant>()
        val predictions = mutableListOf<SlaPrediction>()

        fun add(
            job: BatchJob,
            key: RunKey,
            end: Instant,
            waitingFor: DagId?,
        ) {
            predicted[key] = end
            job.dag.sla
                ?.deadline
                ?.of(key.partition)
                ?.let { predictions += SlaPrediction(key, end, it, waitingFor) }
        }
    }

    /** 実行中・失敗した実行を、記録したステップから予測する。読めなければその失敗を返す。 */
    @Suppress("ReturnCount") // 記録を読めないときは、そこで返す
    private fun predictActive(
        job: BatchJob,
        estimates: StepEstimates,
        round: Round,
    ): BatchError? {
        for (status in ACTIVE) {
            val runs =
                when (val found = store.runs(job.dag.id, status, round.now - lookback)) {
                    is Result.Err -> return found.error
                    is Result.Ok -> found.value
                }
            for (run in runs) {
                when (val steps = store.steps(run.key)) {
                    is Result.Err -> return steps.error
                    is Result.Ok -> round.add(job, run.key, SlaPredictor.predictEnd(job.dag, steps.value, estimates, round.now), null)
                }
            }
        }
        return null
    }

    /** 前段の DAG を待っている、まだ始まっていない実行を、前段の完了の予測から予測する。 */
    @Suppress("ReturnCount") // 前段を待たない DAG・記録を読めないときは、そこで返す
    private fun predictWaiting(
        job: BatchJob,
        estimates: StepEstimates,
        round: Round,
    ): BatchError? {
        val trigger = job.trigger as? Trigger.AfterDag ?: return null
        for ((upstream, upstreamEnd) in round.predicted.filterKeys { it.dag == trigger.upstream }) {
            val key = RunKey(job.dag.id, upstream.partition)
            when (val found = store.find(key)) {
                is Result.Err -> return found.error
                is Result.Ok -> if (found.value != null) continue
            }
            round.add(
                job,
                key,
                SlaPredictor.predictEnd(job.dag, emptyMap(), estimates, round.now, earliestStart = upstreamEnd),
                trigger.upstream,
            )
        }
        return null
    }

    private fun publish(
        predictions: List<SlaPrediction>,
        now: Instant,
    ) {
        val open = predictions.filter { it.deadline > now }
        slack.set(
            open
                .groupBy { it.run.dag }
                .mapValues { (_, runs) -> runs.minOf { it.slack }.toDouble(DurationUnit.SECONDS) },
        )
        open.filter { it.slack.isNegative() }.forEach { prediction ->
            if (warned.add(prediction.run)) {
                logger.warn(
                    "{} は SLA の期限 {} に遅れる見込みです(完了の予測 {}{})",
                    prediction.run,
                    prediction.deadline,
                    prediction.predictedEnd,
                    prediction.waitingFor?.let { "。前段 $it を待っています" } ?: "",
                )
            }
        }
        warned.retainAll(open.filter { it.slack.isNegative() }.map { it.run }.toSet())
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SlaMonitor::class.java)
        private val ACTIVE = listOf(RunStatus.RUNNING, RunStatus.FAILED)
    }
}
