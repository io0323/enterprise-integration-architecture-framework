package io.eia.platform.batch

import io.eia.platform.batch.sla.Sla
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** DAG の ID。`{service}.{job}`(例 `batch-etl.order-daily`)。メトリクスの属性と Checkpoint のキーに使う。 */
@JvmInline
public value class DagId(
    public val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "DAG の ID は {service}.{job}(英小文字・数字・-)にしてください: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$")
    }
}

/** ステップの名前。DAG の中で一意。 */
@JvmInline
public value class StepName(
    public val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "ステップの名前は英小文字・数字・- の 63 文字以内にしてください: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[a-z][a-z0-9-]{0,62}$")
    }
}

/**
 * 実行の単位(パーティション)のキー。1 つの DAG で同じキーの実行は 1 つだけ(ADR-0030 §2)。
 * 日次の DAG は業務の日(`2026-10-13`)、ファイルの到着で動く DAG はファイルの名前(拡張子を除く)にする。
 */
@JvmInline
public value class PartitionKey(
    public val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "パーティションのキーは英数字と . _ - の 128 文字以内にしてください: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

/** 1 つの実行(DAG とパーティション)。 */
public data class RunKey(
    val dag: DagId,
    val partition: PartitionKey,
) {
    override fun toString(): String = "$dag/$partition"
}

/** ステップの処理。業務のエラーは `Result` で返す。例外はリトライできる失敗として扱う(ADR-0030 §3)。 */
public fun interface StepAction {
    public suspend fun execute(context: StepContext): Result<Unit, DomainError>
}

/**
 * ステップの定義。
 *
 * @property dependsOn このステップの前に成功していなければならないステップ
 * @property expected 実績(成功した実行の所要時間)が [io.eia.platform.batch.sla.StepEstimates.MIN_SAMPLES] 件に満たない間の、
 *   所要時間の見込み(SLA の予測に使う)
 * @property retry 失敗したときのリトライ(初回を含む回数。既定 3 回。Framework 7.4)
 * @property timeout 1 回の試行の上限。超えたらリトライできる失敗にする。null なら上限なし
 */
public class StepDefinition internal constructor(
    public val name: StepName,
    public val dependsOn: List<StepName>,
    public val expected: Duration,
    public val retry: RetryPolicy,
    public val timeout: Duration?,
    internal val action: StepAction,
)

/**
 * DAG の定義。ステップは定義した順に並ぶ(依存先は必ず前にあるので、この順はトポロジカル順になる)。
 *
 * @property integrationId メトリクス・ログの `integration_id`(連携カタログの ID)。null なら付けない
 * @property sla 完了の期限。null なら SLA の予測をしない
 */
public class DagDefinition internal constructor(
    public val id: DagId,
    public val steps: List<StepDefinition>,
    public val integrationId: String?,
    public val sla: Sla?,
) {
    public fun step(name: StepName): StepDefinition = steps.first { it.name == name }
}

/** [DagBuilder.step] が返す、後のステップの依存先に指定するための参照。 */
public class StepRef internal constructor(
    internal val owner: DagBuilder,
    public val name: StepName,
)

/**
 * DAG を Kotlin DSL で定義する(Framework 7.2: 依存は DAG で明示し、cron の時刻の暗黙の連鎖を使わない)。
 *
 * ```
 * val daily = dag("batch-etl.order-daily") {
 *     integrationId = "INT-SALES-002"
 *     sla = Sla.dailyAt(LocalTime(6, 0), TimeZone.of("Asia/Tokyo"))
 *     val ingest = step("ingest") { expected = 10.minutes; action { ctx -> ingest(ctx) } }
 *     val load = step("load", ingest) { action { ctx -> load(ctx) } }
 *     step("verify", load) { action { ctx -> verify(ctx) } }
 * }
 * ```
 *
 * 依存先には、同じ DAG で先に定義したステップの参照だけを渡せる。そのため循環は作れない。
 */
public fun dag(
    id: String,
    block: DagBuilder.() -> Unit,
): DagDefinition = DagBuilder(DagId(id)).apply(block).build()

@DslMarker
public annotation class BatchDsl

@BatchDsl
public class DagBuilder internal constructor(
    private val id: DagId,
) {
    private val steps = mutableListOf<StepDefinition>()
    public var integrationId: String? = null
    public var sla: Sla? = null

    public fun step(
        name: String,
        vararg after: StepRef,
        block: StepBuilder.() -> Unit,
    ): StepRef {
        val stepName = StepName(name)
        require(steps.none { it.name == stepName }) { "ステップの名前が重複しています: $name" }
        after.forEach { require(it.owner === this) { "依存先はこの DAG で先に定義したステップにしてください: ${it.name}" } }
        val builder = StepBuilder().apply(block)
        val action = requireNotNull(builder.action) { "ステップ $name の処理(action)がありません" }
        steps += StepDefinition(stepName, after.map { it.name }.distinct(), builder.expected, builder.retry, builder.timeout, action)
        return StepRef(this, stepName)
    }

    internal fun build(): DagDefinition {
        require(steps.isNotEmpty()) { "DAG $id にステップがありません" }
        return DagDefinition(id, steps.toList(), integrationId, sla)
    }
}

@BatchDsl
public class StepBuilder internal constructor() {
    public var expected: Duration = DEFAULT_EXPECTED
        set(value) {
            require(value.isPositive()) { "expected は正の値にしてください: $value" }
            field = value
        }
    public var retry: RetryPolicy = DEFAULT_RETRY
    public var timeout: Duration? = null
        set(value) {
            require(value == null || value.isPositive()) { "timeout は正の値にしてください: $value" }
            field = value
        }
    internal var action: StepAction? = null

    public fun action(action: StepAction) {
        this.action = action
    }

    public companion object {
        /** 実績がない間の所要時間の見込みの既定。 */
        public val DEFAULT_EXPECTED: Duration = 5.minutes

        /**
         * ステップのリトライの既定(Framework 7.4: 自動で 3 回)。バッチの失敗の多くは DB・ストレージの一時的な不調なので、
         * API の既定(500ms から)より長く待つ。
         */
        public val DEFAULT_RETRY: RetryPolicy = RetryPolicy(initialDelay = 5.seconds, maxDelay = 1.minutes)
    }
}
