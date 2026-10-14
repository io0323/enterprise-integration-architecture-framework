package io.eia.platform.batch

import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryDecision
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.semconv.ErrorAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** 実行の仕方。 */
public enum class RunMode {
    /** 成功したステップを飛ばし、残りを実行する(既定)。成功した実行は何もしない。 */
    RESUME,

    /** ステップの記録とパーティションの Checkpoint を消して、最初からやり直す(遅れて届いたデータでの作り直しなど)。 */
    RESTART,
}

/** 実行の結果。 */
public sealed interface RunOutcome {
    public val run: RunKey

    /** @property executed この起動で実行したステップ。@property skipped 前の起動で成功していたので飛ばしたステップ */
    public data class Succeeded(
        override val run: RunKey,
        val generation: Int,
        val executed: List<StepName>,
        val skipped: List<StepName>,
    ) : RunOutcome

    /** [RunMode.RESUME] で、すでに成功していた。 */
    public data class AlreadySucceeded(
        override val run: RunKey,
    ) : RunOutcome
}

/**
 * DAG を 1 つのパーティションについて実行する(Framework 7.2・7.4。ADR-0030 §2〜§5)。
 *
 * - **ロック**: 同じ実行(DAG とパーティション)を同時に 1 つだけにする。取れなければ [BatchRunLocked]。
 * - **Checkpoint と再開**: ステップの成功を記録し、再実行では成功したステップを飛ばす。途中で kill されても、
 *   次の実行が落ちたステップから再開する。ステップの途中の位置は [StepContext.saveCheckpoint] で残せる。
 * - **並列**: 依存先がすべて成功したステップから、並べて実行する。ステップが失敗したら新しいステップは始めず、
 *   実行中のステップの終わりを待って FAILED にする。
 * - **リトライ**: ステップの [StepDefinition.retry](既定 3 回)。リトライできない失敗と、回数を使い切った失敗は運用に引き継ぐ。
 * - **トレース**: 実行の span(`{dag} run`)の親は起動の情報の traceparent(ファイルのマニフェスト・前段の DAG)。
 *   再開しても最初の起動の traceparent を親にするので、同じトレースにつながる。ステップは `{dag} {step}` の子の span。
 */
public class BatchRunner(
    private val store: BatchStore,
    private val observability: ObservabilityRuntime,
    private val clock: Clock = Clock.System,
    private val listener: BatchListener = BatchListener.NONE,
    private val random: Random = Random.Default,
) {
    @Suppress("ReturnCount") // ロックの失敗・取れないときは、実行の前に返す
    public suspend fun run(
        dag: DagDefinition,
        partition: PartitionKey,
        trigger: RunTrigger = RunTrigger.MANUAL,
        mode: RunMode = RunMode.RESUME,
    ): Result<RunOutcome, BatchError> {
        val key = RunKey(dag.id, partition)
        val lease =
            when (val locked = io { store.tryLock(key) }) {
                is Result.Err -> return locked
                is Result.Ok -> locked.value ?: return err(BatchRunLocked(key))
            }
        try {
            return locked(dag, key, trigger, mode)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { lease.close() }
        }
    }

    @Suppress("ReturnCount") // 記録を読めない・成功済みのときは、実行の前に返す
    private suspend fun locked(
        dag: DagDefinition,
        key: RunKey,
        trigger: RunTrigger,
        mode: RunMode,
    ): Result<RunOutcome, BatchError> {
        val existing =
            when (val found = io { store.find(key) }) {
                is Result.Err -> return found
                is Result.Ok -> found.value
            }
        if (existing?.status == RunStatus.SUCCEEDED && mode == RunMode.RESUME) return ok(RunOutcome.AlreadySucceeded(key))
        val restart = existing != null && mode == RunMode.RESTART
        // 再開は最初の起動の情報(traceparent・Correlation ID・属性)のまま続ける
        val resumed = existing != null && !restart
        return traced(dag, key, if (resumed) existing.trigger else trigger, restart, resumed)
    }

    private suspend fun traced(
        dag: DagDefinition,
        key: RunKey,
        origin: RunTrigger,
        restart: Boolean,
        resumed: Boolean,
    ): Result<RunOutcome, BatchError> {
        val correlationId = origin.correlationId?.let { CorrelationId.parse(it).getOrNull() } ?: CorrelationId.generate()
        val context = ObservabilityContext(correlationId, dag.integrationId, parentOf(origin.traceparent))
        return withContext(context) {
            observability.withSpan("${dag.id} run") { span ->
                span.setAttribute(BatchMetrics.DAG, dag.id.value)
                span.setAttribute(PARTITION, key.partition.value)
                span.setAttribute(RESUMED, resumed)
                val started = clock.now()
                val result =
                    when (val begun = io { store.begin(key, origin, traceparentOf(span), restart, started) }) {
                        is Result.Err -> begun
                        is Result.Ok -> execute(dag, begun.value)
                    }
                complete(dag, key, started, result)
                if (result is Result.Err) markError(span, result.error.code)
                result
            }
        }
    }

    /** 実行の終わりを記録し、メトリクスとログに残す。 */
    private suspend fun complete(
        dag: DagDefinition,
        key: RunKey,
        started: Instant,
        result: Result<RunOutcome, BatchError>,
    ) {
        val status = if (result is Result.Ok) RunStatus.SUCCEEDED else RunStatus.FAILED
        // 記録の DB が使えないときは RUNNING のまま残る(次の実行で再開する)
        io { store.finish(key, status, clock.now()) }
        val finished = clock.now()
        val deadline = dag.sla?.deadline?.of(key.partition)
        listener.runFinished(key, status, finished - started, deadline?.let { finished > it })
        when (result) {
            is Result.Ok -> logger.info("{} の実行が成功しました", key)

            // 運用に引き継ぐ(再実行すると、失敗したステップから再開する。docs/runbooks/batch-rerun.md)
            is Result.Err -> logger.error("{} の実行が失敗しました({})", key, result.error.message)
        }
    }

    private suspend fun execute(
        dag: DagDefinition,
        record: RunRecord,
    ): Result<RunOutcome, BatchError> {
        val key = record.key
        val done =
            when (val steps = io { store.steps(key) }) {
                is Result.Err -> return steps
                is Result.Ok -> steps.value.filterValues { it.status == StepStatus.SUCCEEDED }.keys
            }
        val skipped = dag.steps.map { it.name }.filter { it in done }
        return coroutineScope {
            val completed = done.toMutableSet()
            val launched = done.toMutableSet()
            val executed = mutableListOf<StepName>()
            val results = Channel<Pair<StepName, Result<Unit, BatchError>>>(Channel.UNLIMITED)
            var running = 0
            var failure: BatchError? = null

            fun launchReady() {
                if (failure != null) return
                dag.steps
                    .filter { it.name !in launched && completed.containsAll(it.dependsOn) }
                    .forEach { step ->
                        launched += step.name
                        running++
                        launch { results.send(step.name to runStep(dag, step, record)) }
                    }
            }

            launchReady()
            while (running > 0) {
                val (name, result) = results.receive()
                running--
                when (result) {
                    is Result.Ok -> {
                        completed += name
                        executed += name
                    }

                    is Result.Err -> {
                        if (failure == null) failure = result.error
                    }
                }
                launchReady()
            }
            failure?.let { err(it) } ?: ok(RunOutcome.Succeeded(key, record.generation, executed, skipped))
        }
    }

    @Suppress("ReturnCount") // 記録の失敗・成功・諦めのそれぞれで返す
    private suspend fun runStep(
        dag: DagDefinition,
        step: StepDefinition,
        record: RunRecord,
    ): Result<Unit, BatchError> {
        val key = record.key
        val started = clock.now()
        var attempt = 1
        while (true) {
            io { store.stepStarted(key, step.name, attempt, clock.now()) }.let { if (it is Result.Err) return it }
            val outcome = tryOnce(dag, step, StepContext(record, step.name, attempt, store, clock))
            if (outcome is Result.Ok) return finishStep(key, step, StepStatus.SUCCEEDED, started)
            val error = (outcome as Result.Err).error
            val decision = step.retry.decide(attempt, error, random)
            if (decision !is RetryDecision.Retry) {
                finishStep(key, step, StepStatus.FAILED, started)
                return err(BatchStepFailed(key, step.name, attempt, error))
            }
            listener.stepRetried(key, step.name, error)
            logger.warn("{} のステップ {} が失敗しました({})。{} の後にリトライします({} 回目)", key, step.name, error.code, decision.delay, attempt)
            delay(decision.delay)
            attempt++
        }
    }

    /** ステップの 1 回の試行(span `{dag} {step}`)。 */
    private suspend fun tryOnce(
        dag: DagDefinition,
        step: StepDefinition,
        context: StepContext,
    ): Result<Unit, DomainError> =
        observability.withSpan("${dag.id} ${step.name}") { span ->
            span.setAttribute(BatchMetrics.DAG, dag.id.value)
            span.setAttribute(BatchMetrics.STEP, step.name.value)
            span.setAttribute(PARTITION, context.run.partition.value)
            span.setAttribute(ATTEMPT, context.attempt.toLong())
            attempt(step, context).also { if (it is Result.Err) markError(span, it.error.code) }
        }

    private suspend fun finishStep(
        key: RunKey,
        step: StepDefinition,
        status: StepStatus,
        started: Instant,
    ): Result<Unit, BatchError> {
        val result = io { store.stepFinished(key, step.name, status, clock.now()) }
        listener.stepFinished(key, step.name, status, clock.now() - started)
        return result
    }

    @Suppress("TooGenericExceptionCaught") // ステップの例外は、どの種類でもリトライできる失敗にする(キャンセルは除く)
    private suspend fun attempt(
        step: StepDefinition,
        context: StepContext,
    ): Result<Unit, DomainError> =
        try {
            val timeout = step.timeout
            if (timeout == null) {
                step.action.execute(context)
            } else {
                withTimeoutOrNull(timeout) { step.action.execute(context) } ?: err(StepTimedOut(timeout))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err(StepThrew(e::class.simpleName ?: "Exception"))
        }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        private val logger = LoggerFactory.getLogger(BatchRunner::class.java)
        private val PARTITION = AttributeKey.stringKey("batch.partition")
        private val RESUMED = AttributeKey.booleanKey("batch.run.resumed")
        private val ATTEMPT = AttributeKey.longKey("batch.step.attempt")
        private val W3C = W3CTraceContextPropagator.getInstance()
        private const val TRACEPARENT = "traceparent"

        fun markError(
            span: Span,
            code: String,
        ) {
            span.setAttribute(ErrorAttributes.ERROR_TYPE, code)
            span.setStatus(StatusCode.ERROR)
        }

        /** 起動の情報の traceparent を親の Context にする。なければ(・読めなければ)新しいトレースを始める。 */
        fun parentOf(traceparent: String?): Context =
            if (traceparent == null) Context.root() else W3C.extract(Context.root(), mapOf(TRACEPARENT to traceparent), MapGetter)

        fun traceparentOf(span: Span): String? {
            val carrier = mutableMapOf<String, String>()
            W3C.inject(Context.root().with(span), carrier) { map, name, value -> map?.put(name, value) }
            return carrier[TRACEPARENT]
        }
    }

    private object MapGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

        override fun get(
            carrier: Map<String, String>?,
            key: String,
        ): String? = carrier?.get(key)
    }
}
