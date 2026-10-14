package io.eia.platform.batch

import io.eia.shared.kernel.DomainError
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** [BatchRunner] の実行の結果の通知。メトリクス([BatchMetrics])に使う。 */
public interface BatchListener {
    public fun stepFinished(
        run: RunKey,
        step: StepName,
        status: StepStatus,
        duration: Duration,
    ) {}

    public fun stepRetried(
        run: RunKey,
        step: StepName,
        error: DomainError,
    ) {}

    /** @param deadlineMissed SLA の期限を過ぎて終わったか。期限のない実行は null */
    public fun runFinished(
        run: RunKey,
        status: RunStatus,
        duration: Duration,
        deadlineMissed: Boolean?,
    ) {}

    public companion object {
        public val NONE: BatchListener = object : BatchListener {}
    }
}

/**
 * バッチのメトリクス。`platform/observability` の `ObservabilityRuntime.meter` を渡して作る。
 * 属性にパーティション(日付・ファイル名)は入れない(系列が増え続けるため。どの実行かはトレースとログで見る)。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.batch.runs` | counter | `batch.dag`・`batch.run.status`(`succeeded` / `failed`) |
 * | `eia.batch.run.duration` | histogram(秒) | `batch.dag`・`batch.run.status`。この起動での所要時間(再開した実行は再開からの時間) |
 * | `eia.batch.sla.missed` | counter | `batch.dag`。SLA の期限を過ぎて終わった実行 |
 * | `eia.batch.step.duration` | histogram(秒) | `batch.dag`・`batch.step`・`batch.step.status` |
 * | `eia.batch.step.retries` | counter | `batch.dag`・`batch.step`・`error.code` |
 *
 * SLA の予測(`eia.batch.sla.slack`)は [io.eia.platform.batch.sla.SlaMonitor] が出す。
 */
public class BatchMetrics(
    meter: Meter,
) : BatchListener {
    private val runs: LongCounter =
        meter
            .counterBuilder("eia.batch.runs")
            .setUnit("{run}")
            .setDescription("終わったバッチの実行の件数")
            .build()
    private val runDuration: DoubleHistogram =
        meter
            .histogramBuilder("eia.batch.run.duration")
            .setUnit("s")
            .setDescription("バッチの実行の所要時間")
            .build()
    private val missed: LongCounter =
        meter
            .counterBuilder("eia.batch.sla.missed")
            .setUnit("{run}")
            .setDescription("SLA の期限を過ぎて終わったバッチの実行の件数")
            .build()
    private val stepDuration: DoubleHistogram =
        meter
            .histogramBuilder("eia.batch.step.duration")
            .setUnit("s")
            .setDescription("バッチのステップの所要時間(リトライを含む)")
            .build()
    private val retries: LongCounter =
        meter
            .counterBuilder("eia.batch.step.retries")
            .setUnit("{retry}")
            .setDescription("バッチのステップのリトライの回数")
            .build()

    override fun stepFinished(
        run: RunKey,
        step: StepName,
        status: StepStatus,
        duration: Duration,
    ) {
        stepDuration.record(
            duration.toDouble(DurationUnit.SECONDS),
            Attributes.of(DAG, run.dag.value, STEP, step.value, STEP_STATUS, status.name.lowercase()),
        )
    }

    override fun stepRetried(
        run: RunKey,
        step: StepName,
        error: DomainError,
    ) {
        retries.add(1, Attributes.of(DAG, run.dag.value, STEP, step.value, ERROR_CODE, error.code))
    }

    override fun runFinished(
        run: RunKey,
        status: RunStatus,
        duration: Duration,
        deadlineMissed: Boolean?,
    ) {
        val attributes = Attributes.of(DAG, run.dag.value, RUN_STATUS, status.name.lowercase())
        runs.add(1, attributes)
        runDuration.record(duration.toDouble(DurationUnit.SECONDS), attributes)
        if (deadlineMissed == true) missed.add(1, Attributes.of(DAG, run.dag.value))
    }

    public companion object {
        public val DAG: AttributeKey<String> = AttributeKey.stringKey("batch.dag")
        public val STEP: AttributeKey<String> = AttributeKey.stringKey("batch.step")
        private val RUN_STATUS: AttributeKey<String> = AttributeKey.stringKey("batch.run.status")
        private val STEP_STATUS: AttributeKey<String> = AttributeKey.stringKey("batch.step.status")
        private val ERROR_CODE: AttributeKey<String> = AttributeKey.stringKey("error.code")
    }
}
