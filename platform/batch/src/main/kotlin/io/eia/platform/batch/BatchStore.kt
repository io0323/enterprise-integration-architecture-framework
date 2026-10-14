package io.eia.platform.batch

import io.eia.shared.kernel.Result
import kotlin.time.Duration
import kotlin.time.Instant

/** 実行の状態。 */
public enum class RunStatus {
    /** 実行中。プロセスが落ちた実行もこのまま残り、次の実行で再開する(ロックが空いていれば落ちたと分かる)。 */
    RUNNING,
    SUCCEEDED,

    /** ステップがリトライの後も失敗した。再実行すると、失敗したステップから再開する。 */
    FAILED,
}

/** ステップの状態。記録がないステップは、まだ始めていない。 */
public enum class StepStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

/** 何が実行を起動したか。 */
public enum class TriggerKind {
    /** 時刻([io.eia.platform.batch.trigger.Schedule])。 */
    SCHEDULE,

    /** 前段の DAG の同じパーティションの完了。 */
    UPSTREAM,

    /** ファイル(`.manifest.json`)の到着。 */
    FILE,

    /** 運用者・テストの明示的な実行。 */
    MANUAL,
}

/**
 * 実行の起動の情報(ジョブの属性)。実行の記録に保存し、再開しても同じ値を使う(ADR-0030 §5)。
 *
 * @property traceparent 実行の span の親(ファイルのマニフェスト・前段の DAG の実行の span)。null なら新しいトレースを始める
 * @property correlationId 業務の Correlation ID。null なら採番する
 * @property attributes ステップに渡す値(ファイルのキーなど)。業務の値・秘密情報は入れない
 */
public data class RunTrigger(
    val kind: TriggerKind,
    val traceparent: String? = null,
    val correlationId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    public companion object {
        public val MANUAL: RunTrigger = RunTrigger(TriggerKind.MANUAL)
    }
}

/**
 * 実行の記録。
 *
 * @property generation やり直し([RunMode.RESTART])のたびに 1 増える
 * @property runTraceparent 最後に実行した span(後段の DAG の span の親にする)
 */
public data class RunRecord(
    val key: RunKey,
    val status: RunStatus,
    val generation: Int,
    val trigger: RunTrigger,
    val runTraceparent: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

/** ステップの記録。[attempts] は今の世代での試行の回数。 */
public data class StepRecord(
    val name: StepName,
    val status: StepStatus,
    val attempts: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

/**
 * Checkpoint のキー。
 *
 * - [partition] あり: 実行(パーティション)のステップの途中の位置(例 処理済みのファイルの行)。やり直し([RunMode.RESTART])で消える。
 * - [partition] なし: DAG 全体の Watermark(例 取り込んだ位置)。パーティションをまたいで残り、やり直しでも消えない。
 */
public data class CheckpointKey(
    val dag: DagId,
    val partition: PartitionKey?,
    val name: String,
) {
    init {
        require(NAME.matches(name)) { "Checkpoint の名前は英小文字・数字・. _ - の 128 文字以内にしてください: $name" }
    }

    private companion object {
        val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
    }
}

/** 実行のロック。[close] で放す。プロセスが落ちれば DB の接続が切れて放される。 */
public fun interface RunLease : AutoCloseable {
    override fun close()
}

/**
 * 実行の記録・ロック・Checkpoint の保存先。本番は `JdbcBatchStore`(PostgreSQL)。
 *
 * 時刻は呼び出し側の時計([kotlin.time.Clock])で渡す(所要時間とテストの時刻を揃えるため)。
 */
@Suppress("TooManyFunctions") // 実行・ステップ・Checkpoint・実績の記録を 1 つの保存先にまとめる(同じ DB・同じトランザクションで書けるように)
public interface BatchStore {
    /** [run] のロックを取る。ほかのインスタンスが持っていれば null。 */
    public fun tryLock(run: RunKey): Result<RunLease?, BatchError>

    public fun find(run: RunKey): Result<RunRecord?, BatchError>

    /**
     * 実行を始める(ロックを持っている間だけ呼ぶ)。
     *
     * - 記録がなければ、世代 1 で作る。
     * - [restart] が false なら、記録を RUNNING に戻して再開する(成功したステップと Checkpoint は残す。起動の情報は最初のまま)。
     * - [restart] が true なら、世代を 1 増やし、ステップの記録とパーティションの Checkpoint を消して、[trigger] で始め直す。
     */
    public fun begin(
        run: RunKey,
        trigger: RunTrigger,
        runTraceparent: String?,
        restart: Boolean,
        now: Instant,
    ): Result<RunRecord, BatchError>

    public fun steps(run: RunKey): Result<Map<StepName, StepRecord>, BatchError>

    /** ステップの [attempt] 回目の試行を始めた。 */
    public fun stepStarted(
        run: RunKey,
        step: StepName,
        attempt: Int,
        now: Instant,
    ): Result<Unit, BatchError>

    public fun stepFinished(
        run: RunKey,
        step: StepName,
        status: StepStatus,
        now: Instant,
    ): Result<Unit, BatchError>

    public fun finish(
        run: RunKey,
        status: RunStatus,
        now: Instant,
    ): Result<Unit, BatchError>

    public fun readCheckpoint(key: CheckpointKey): Result<String?, BatchError>

    /**
     * Checkpoint を書く(この呼び出しだけで確定する)。ステップの結果と同時に確定させたいときは、
     * 実装ごとの「同じトランザクションで書く」関数(`JdbcBatchStore.writeCheckpoint(connection, ...)`)を使う。
     */
    public fun writeCheckpoint(
        key: CheckpointKey,
        value: String,
        now: Instant,
    ): Result<Unit, BatchError>

    /** [step] の、成功した最近 [limit] 回の所要時間(新しい順)。SLA の予測の実績に使う。 */
    public fun recentDurations(
        dag: DagId,
        step: StepName,
        limit: Int,
    ): Result<List<Duration>, BatchError>

    /** [dag] の状態が [status] の実行のうち、[since] 以降に始まったか終わったもの。 */
    public fun runs(
        dag: DagId,
        status: RunStatus,
        since: Instant,
    ): Result<List<RunRecord>, BatchError>
}
