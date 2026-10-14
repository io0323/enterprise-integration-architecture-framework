package io.eia.platform.batch

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Instant

/** テスト用の [BatchStore]。[failing] を設定すると、すべての操作がその失敗を返す。 */
internal class InMemoryBatchStore : BatchStore {
    val runs = mutableMapOf<RunKey, RunRecord>()
    val steps = mutableMapOf<RunKey, MutableMap<StepName, StepRecord>>()
    val checkpoints = mutableMapOf<CheckpointKey, String>()
    val locks = mutableSetOf<RunKey>()
    var failing: BatchError? = null

    /** 過去の成功の所要時間(SLA の実績)。 */
    val history = mutableMapOf<Pair<DagId, StepName>, List<Duration>>()

    @Synchronized
    private fun <T> guarded(block: () -> T): Result<T, BatchError> = failing?.let { err(it) } ?: ok(block())

    override fun tryLock(run: RunKey): Result<RunLease?, BatchError> =
        guarded {
            if (locks.add(run)) RunLease { synchronized(this) { locks.remove(run) } } else null
        }

    override fun find(run: RunKey): Result<RunRecord?, BatchError> = guarded { runs[run] }

    override fun begin(
        run: RunKey,
        trigger: RunTrigger,
        runTraceparent: String?,
        restart: Boolean,
        now: Instant,
    ): Result<RunRecord, BatchError> =
        guarded {
            val existing = runs[run]
            val record =
                when {
                    existing == null -> {
                        RunRecord(run, RunStatus.RUNNING, 1, trigger, runTraceparent, now, null)
                    }

                    restart -> {
                        steps.remove(run)
                        checkpoints.keys.removeIf { it.dag == run.dag && it.partition == run.partition }
                        RunRecord(run, RunStatus.RUNNING, existing.generation + 1, trigger, runTraceparent, now, null)
                    }

                    else -> {
                        existing.copy(status = RunStatus.RUNNING, runTraceparent = runTraceparent, finishedAt = null)
                    }
                }
            runs[run] = record
            record
        }

    override fun steps(run: RunKey): Result<Map<StepName, StepRecord>, BatchError> = guarded { steps[run].orEmpty().toMap() }

    override fun stepStarted(
        run: RunKey,
        step: StepName,
        attempt: Int,
        now: Instant,
    ): Result<Unit, BatchError> =
        guarded {
            val records = steps.getOrPut(run) { mutableMapOf() }
            val startedAt = if (attempt == 1) now else records[step]?.startedAt ?: now
            records[step] = StepRecord(step, StepStatus.RUNNING, attempt, startedAt, null)
        }

    override fun stepFinished(
        run: RunKey,
        step: StepName,
        status: StepStatus,
        now: Instant,
    ): Result<Unit, BatchError> =
        guarded {
            val records = steps.getValue(run)
            records[step] = records.getValue(step).copy(status = status, finishedAt = now)
        }

    override fun finish(
        run: RunKey,
        status: RunStatus,
        now: Instant,
    ): Result<Unit, BatchError> =
        guarded {
            runs[run] = runs.getValue(run).copy(status = status, finishedAt = now)
        }

    override fun readCheckpoint(key: CheckpointKey): Result<String?, BatchError> = guarded { checkpoints[key] }

    override fun writeCheckpoint(
        key: CheckpointKey,
        value: String,
        now: Instant,
    ): Result<Unit, BatchError> = guarded { checkpoints[key] = value }

    override fun recentDurations(
        dag: DagId,
        step: StepName,
        limit: Int,
    ): Result<List<Duration>, BatchError> = guarded { history[dag to step].orEmpty().take(limit) }

    override fun runs(
        dag: DagId,
        status: RunStatus,
        since: Instant,
    ): Result<List<RunRecord>, BatchError> =
        guarded {
            runs.values
                .filter { it.key.dag == dag && it.status == status && (it.finishedAt ?: it.startedAt) >= since }
                .sortedBy { it.key.partition.value }
        }
}
