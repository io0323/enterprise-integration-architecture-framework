package io.eia.platform.batch.sla

import io.eia.platform.batch.BatchError
import io.eia.platform.batch.BatchStore
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.StepName
import io.eia.platform.batch.StepRecord
import io.eia.platform.batch.StepStatus
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * ステップごとの所要時間の見込み(ADR-0030 §6)。成功した最近 [WINDOW] 回の実績が [MIN_SAMPLES] 回以上あれば、その p90。
 * それより少なければ、DAG の定義の見込み(`expected`)。
 */
public class StepEstimates(
    private val byStep: Map<StepName, Duration>,
) {
    public operator fun get(step: StepName): Duration = byStep.getValue(step)

    public companion object {
        public const val MIN_SAMPLES: Int = 5
        public const val WINDOW: Int = 20
        private const val PERCENTILE = 0.9

        public fun of(
            dag: DagDefinition,
            store: BatchStore,
        ): Result<StepEstimates, BatchError> {
            val estimates = mutableMapOf<StepName, Duration>()
            for (step in dag.steps) {
                when (val samples = store.recentDurations(dag.id, step.name, WINDOW)) {
                    is Result.Err -> return err(samples.error)
                    is Result.Ok -> estimates[step.name] = if (samples.value.size >= MIN_SAMPLES) p90(samples.value) else step.expected
                }
            }
            return ok(StepEstimates(estimates))
        }

        /** 最近傍順位法の p90(並べた値の ceil(0.9 * n) 番目)。 */
        public fun p90(samples: List<Duration>): Duration {
            require(samples.isNotEmpty()) { "実績がありません" }
            val sorted = samples.sorted()
            return sorted[ceil(PERCENTILE * sorted.size).toInt() - 1]
        }
    }
}

/**
 * 実行の完了の時刻の予測(ADR-0030 §6)。DAG の依存をたどり、ステップごとに次のように見込む。
 *
 * - 成功したステップ: 実際に終わった時刻。
 * - 実行中のステップ: 始めた時刻 + 見込み。見込みを過ぎていれば今(まだ終わっていないので、少なくとも今より後)。
 * - まだ始めていない(失敗してやり直す)ステップ: 依存先がすべて終わる見込みの時刻(と今・[earliestStart] の遅い方)に始め、見込みだけかかる。
 *
 * そのため、前段のステップ・前段の DAG が遅れると、まだ始めていないステップの開始の待ちが延び、完了の予測も後ろにずれる
 * (条件: 前段の遅れによる待ちを予測に含める)。完了の予測は、全ステップの終わりの見込みの最大。
 */
public object SlaPredictor {
    /**
     * @param earliestStart まだ始まっていない実行が始められる見込みの時刻(前段の DAG の完了の予測)。始まっている実行では null
     */
    public fun predictEnd(
        dag: DagDefinition,
        steps: Map<StepName, StepRecord>,
        estimates: StepEstimates,
        now: Instant,
        earliestStart: Instant? = null,
    ): Instant {
        val floor = maxOf(now, earliestStart ?: now)
        val ends = mutableMapOf<StepName, Instant>()
        for (step in dag.steps) {
            val record = steps[step.name]
            val estimate = estimates[step.name]
            ends[step.name] =
                when (record?.status) {
                    StepStatus.SUCCEEDED -> {
                        record.finishedAt ?: now
                    }

                    StepStatus.RUNNING -> {
                        maxOf(record.startedAt + estimate, now)
                    }

                    StepStatus.FAILED, null -> {
                        val ready = step.dependsOn.maxOfOrNull { ends.getValue(it) } ?: floor
                        maxOf(ready, floor) + estimate
                    }
                }
        }
        return ends.values.max()
    }
}
