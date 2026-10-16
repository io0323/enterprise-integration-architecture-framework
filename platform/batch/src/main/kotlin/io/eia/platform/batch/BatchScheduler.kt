package io.eia.platform.batch

import io.eia.platform.batch.sla.SlaMonitor
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.StoreRejected
import io.eia.platform.filetransfer.StoreUnavailable
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 起動する実行。 */
public data class DueRun(
    val job: BatchJob,
    val partition: PartitionKey,
    val trigger: RunTrigger,
    val mode: RunMode = RunMode.RESUME,
) {
    val key: RunKey get() = RunKey(job.dag.id, partition)
}

/**
 * [jobs] の起動の条件([Trigger])を [Settings.pollInterval] ごとに確かめ、起動する実行を [BatchRunner] で動かす(ADR-0030 §4)。
 * 同じ回で [SlaMonitor] の予測も更新する。
 *
 * 起動するのは、パーティションの実行の記録がないか、RUNNING のまま残っている(プロセスが落ちた)ときだけ。
 * FAILED の実行は自動では再実行しない(運用に引き継ぐ。Framework 7.4)。複数のインスタンスで動かしても、
 * 実行のロックで同じ実行は 1 つだけになる(ロックを取れなかった起動は何もしない)。
 *
 * 判定は実行の記録(DB)を見るので、前段の DAG がほかのプロセスで動いていても、同じ DB を使っていれば後段を起動できる。
 *
 */
public class BatchScheduler(
    private val runner: BatchRunner,
    private val store: BatchStore,
    jobs: List<BatchJob>,
    private val clock: Clock = Clock.System,
    private val monitor: SlaMonitor? = null,
    private val settings: Settings = Settings(),
) {
    /**
     * @property pollInterval 起動の条件を確かめる間隔
     * @property lookback 前段の完了を探す期間。これより前に成功した前段の後段は、自動では起動しない
     */
    public data class Settings(
        val pollInterval: Duration = 30.seconds,
        val lookback: Duration = 7.days,
    ) {
        init {
            require(pollInterval.isPositive()) { "pollInterval は正の値にしてください: $pollInterval" }
            require(lookback.isPositive()) { "lookback は正の値にしてください: $lookback" }
        }
    }

    private val jobs: List<BatchJob> = upstreamFirst(jobs)
    private val inFlight = ConcurrentHashMap.newKeySet<RunKey>()

    /** 拒否したマニフェスト(同じ理由のログを繰り返さない)。 */
    private val rejected = ConcurrentHashMap.newKeySet<String>()

    /** キャンセルされるまで、起動の条件を確かめて実行を動かす。実行中の実行もキャンセルされる(RUNNING のまま残り、次の起動で再開する)。 */
    public suspend fun run(): Unit =
        coroutineScope {
            while (isActive) {
                due().forEach { due ->
                    if (inFlight.add(due.key)) {
                        launch {
                            try {
                                execute(due)
                            } finally {
                                inFlight.remove(due.key)
                            }
                        }
                    }
                }
                monitor?.let { withContext(Dispatchers.IO) { it.evaluate() } }
                delay(settings.pollInterval)
            }
        }

    /** 今起動する実行(この中で動かしている実行は除く)。確かめられなかった起動の条件はログに残して飛ばす。 */
    public suspend fun due(): List<DueRun> =
        withContext(Dispatchers.IO) {
            val now = clock.now()
            jobs.flatMap { job ->
                val found =
                    when (val trigger = job.trigger) {
                        is Trigger.Scheduled -> scheduled(job, trigger, now)
                        is Trigger.AfterDag -> afterDag(job, trigger, now)
                        is Trigger.FileArrival -> fileArrival(job, trigger)
                    }
                when (found) {
                    is Result.Ok -> {
                        found.value.filterNot { it.key in inFlight }
                    }

                    is Result.Err -> {
                        logger.warn("{} の起動の条件を確かめられません({})", job.dag.id, found.error.code)
                        emptyList()
                    }
                }
            }
        }

    private suspend fun execute(due: DueRun) {
        when (val result = runner.run(due.job.dag, due.partition, due.trigger, due.mode)) {
            is Result.Ok -> Unit

            // ほかのインスタンスが実行している。そちらに任せる
            is Result.Err -> if (result.error is BatchRunLocked) logger.debug("{} はほかのインスタンスが実行中です", due.key)
        }
    }

    /** 記録がないか、RUNNING のまま残っている(落ちた・ほかのインスタンスが実行中)なら起動する。 */
    private fun startable(key: RunKey): Result<Boolean, BatchError> =
        when (val found = store.find(key)) {
            is Result.Err -> found
            is Result.Ok -> ok(found.value.let { it == null || it.status == RunStatus.RUNNING })
        }

    private fun scheduled(
        job: BatchJob,
        trigger: Trigger.Scheduled,
        now: Instant,
    ): Result<List<DueRun>, BatchError> {
        val occurrence = trigger.schedule.latest(now)
        return when (val startable = startable(RunKey(job.dag.id, occurrence.partition))) {
            is Result.Err -> {
                startable
            }

            is Result.Ok -> {
                ok(
                    if (startable.value) {
                        listOf(
                            DueRun(
                                job,
                                occurrence.partition,
                                RunTrigger(
                                    TriggerKind.SCHEDULE,
                                    attributes =
                                        mapOf(SCHEDULED_AT to "${occurrence.time}"),
                                ),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    @Suppress("ReturnCount") // 記録を読めないときは、そこで返す
    private fun afterDag(
        job: BatchJob,
        trigger: Trigger.AfterDag,
        now: Instant,
    ): Result<List<DueRun>, BatchError> {
        val upstreamRuns =
            when (val runs = store.runs(trigger.upstream, RunStatus.SUCCEEDED, now - settings.lookback)) {
                is Result.Err -> return runs
                is Result.Ok -> runs.value
            }
        val due = mutableListOf<DueRun>()
        for (upstream in upstreamRuns) {
            val key = RunKey(job.dag.id, upstream.key.partition)
            val existing =
                when (val found = store.find(key)) {
                    is Result.Err -> return found
                    is Result.Ok -> found.value
                }
            val runTrigger =
                RunTrigger(
                    TriggerKind.UPSTREAM,
                    traceparent = upstream.runTraceparent,
                    correlationId = upstream.trigger.correlationId,
                    attributes = mapOf(UPSTREAM_DAG to upstream.key.dag.value, UPSTREAM_GENERATION to "${upstream.generation}"),
                )
            val seenGeneration =
                existing
                    ?.trigger
                    ?.attributes
                    ?.get(UPSTREAM_GENERATION)
                    ?.toIntOrNull() ?: 0
            when {
                existing == null || existing.status == RunStatus.RUNNING -> due += DueRun(job, key.partition, runTrigger)

                // 前段が作り直した: 後段も作り直す(FAILED の後段も、前段の新しい結果で始め直す)
                upstream.generation > seenGeneration -> due += DueRun(job, key.partition, runTrigger, RunMode.RESTART)
            }
        }
        return ok(due)
    }

    @Suppress("ReturnCount") // 置き場・記録を読めないときは、そこで返す
    private fun fileArrival(
        job: BatchJob,
        trigger: Trigger.FileArrival,
    ): Result<List<DueRun>, BatchError> {
        val objects =
            when (val listed = trigger.source.list(trigger.prefix).asBatchError()) {
                is Result.Err -> return listed
                is Result.Ok -> listed.value
            }
        val sizes = objects.associate { it.key to it.size }
        val due = mutableListOf<DueRun>()
        for (manifestKey in objects.map { it.key }.filter { it.endsWith(FileManifest.SUFFIX) }) {
            when (val found = manifestDue(job, trigger, manifestKey, sizes)) {
                is Result.Err -> return found
                is Result.Ok -> found.value?.let { due += it }
            }
        }
        return ok(due)
    }

    /** 1 つのマニフェストで起動するか。起動しないなら null。 */
    @Suppress("ReturnCount") // 名前の誤り・実行がある・記録を読めないときは、マニフェストを読む前に返す
    private fun manifestDue(
        job: BatchJob,
        trigger: Trigger.FileArrival,
        manifestKey: String,
        sizes: Map<String, Long>,
    ): Result<DueRun?, BatchError> {
        val name = manifestKey.removePrefix(trigger.prefix).removeSuffix(FileManifest.SUFFIX)
        if (!PARTITION.matches(name)) return ok(reject(job, manifestKey, "名前がパーティションのキーの形ではありません"))
        val partition = PartitionKey(name)
        when (val startable = startable(RunKey(job.dag.id, partition))) {
            is Result.Err -> return startable
            is Result.Ok -> if (!startable.value) return ok(null)
        }
        return when (val arrived = arrival(job, trigger, manifestKey, name, sizes)) {
            is Result.Err -> arrived
            is Result.Ok -> ok(arrived.value?.let { DueRun(job, partition, it) })
        }
    }

    /** マニフェストを読み、データファイルが揃っていれば起動の情報を返す。揃っていなければ null(ログに残す)。 */
    @Suppress("ReturnCount") // 読めない・消えた・形式の誤り・データファイルの誤りのそれぞれで返す
    private fun arrival(
        job: BatchJob,
        trigger: Trigger.FileArrival,
        manifestKey: String,
        name: String,
        sizes: Map<String, Long>,
    ): Result<RunTrigger?, BatchError> {
        val bytes =
            when (val read = trigger.source.read(manifestKey, FileManifest.MAX_BYTES).asBatchError()) {
                is Result.Err -> return read

                // 一覧の後に消された
                is Result.Ok -> read.value ?: return ok(null)
            }
        val manifest =
            when (val parsed = FileManifest.parse(bytes)) {
                is Result.Err -> return ok(reject(job, manifestKey, parsed.error.message))
                is Result.Ok -> parsed.value
            }
        val dataKey = trigger.prefix + manifest.file
        val size = sizes[dataKey]
        val problem =
            when {
                !manifest.file.startsWith("$name.") -> "データファイルの名前がマニフェストの名前と違います"
                !trigger.requireDataFile -> null
                size == null -> "データファイルがありません"
                manifest.sizeBytes != null && manifest.sizeBytes != size -> "データファイルの大きさがマニフェストと違います"
                else -> null
            }
        if (problem != null) return ok(reject(job, manifestKey, problem))
        return ok(
            RunTrigger(
                TriggerKind.FILE,
                traceparent = manifest.traceparent,
                correlationId = manifest.correlationId,
                attributes =
                    buildMap {
                        put(FILE_KEY, dataKey)
                        put(FILE_MANIFEST, manifestKey)
                        put(FILE_SHA256, manifest.sha256)
                        put(FILE_RECORD_COUNT, "${manifest.recordCount}")
                        put(FILE_SCHEMA_VERSION, manifest.schemaVersion)
                    },
            ),
        )
    }

    /** 起動しないマニフェストをログに残す(同じ理由は 1 回だけ)。null を返す。 */
    private fun reject(
        job: BatchJob,
        manifestKey: String,
        reason: String,
    ): Nothing? {
        if (rejected.add("${job.dag.id} $manifestKey $reason")) {
            logger.warn("{} はマニフェスト {} で起動しません: {}", job.dag.id, manifestKey, reason)
        }
        return null
    }

    public companion object {
        /** 実行の属性の名前(ステップは [StepContext.trigger] の attributes で読む)。 */
        public const val SCHEDULED_AT: String = "schedule.at"
        public const val UPSTREAM_DAG: String = "upstream.dag"
        public const val UPSTREAM_GENERATION: String = "upstream.generation"
        public const val FILE_KEY: String = "file.key"
        public const val FILE_MANIFEST: String = "file.manifest"
        public const val FILE_SHA256: String = "file.sha256"
        public const val FILE_RECORD_COUNT: String = "file.recordCount"
        public const val FILE_SCHEMA_VERSION: String = "file.schemaVersion"

        private val logger = LoggerFactory.getLogger(BatchScheduler::class.java)
        private val PARTITION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

/** 置き場(platform/file-transfer)の失敗を、バッチの失敗に写す。一時的な失敗は次の確認でやり直す。 */
private fun <T> Result<T, FileTransferError>.asBatchError(): Result<T, BatchError> =
    when (this) {
        is Result.Ok -> {
            this
        }

        is Result.Err -> {
            Result.Err(
                when (val error = error) {
                    is StoreUnavailable -> {
                        BatchSourceUnavailable(error.reason)
                    }

                    is StoreRejected -> {
                        BatchSourceRejected(error.reason)
                    }

                    else -> {
                        if (error.asDomainError() is DomainError.Retryable) {
                            BatchSourceUnavailable(error.code)
                        } else {
                            BatchSourceRejected(error.code)
                        }
                    }
                },
            )
        }
    }
