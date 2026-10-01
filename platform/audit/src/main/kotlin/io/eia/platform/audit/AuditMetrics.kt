package io.eia.platform.audit

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.time.Duration

/**
 * 監査のメトリクス(ADR-0017 §8 の A17-5)。`platform/observability` の `ObservabilityRuntime.meter` を渡して作る。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.audit.append.duration` | histogram(秒) | なし。追記の全体の所要時間 |
 * | `eia.audit.lock.wait` | histogram(秒) | なし。チェーンのロックの待ち時間(ADR-0017 §4 の直列化の影響) |
 * | `eia.audit.append.failures` | counter | `error.code`(`audit_invalid_event` / `audit_misuse` / `audit_storage_unavailable`) |
 *
 * 属性は決まった値だけにする(記録の中身・ID は入れない)。
 */
public class AuditMetrics(
    meter: Meter,
) : AuditLogListener {
    private val duration: DoubleHistogram =
        meter
            .histogramBuilder("eia.audit.append.duration")
            .setUnit("s")
            .setDescription("監査の追記の所要時間")
            .build()
    private val lockWait: DoubleHistogram =
        meter
            .histogramBuilder("eia.audit.lock.wait")
            .setUnit("s")
            .setDescription("監査のチェーンのロックを取るまでの待ち時間")
            .build()
    private val failures: LongCounter =
        meter
            .counterBuilder("eia.audit.append.failures")
            .setUnit("{append}")
            .setDescription("監査の追記の失敗の件数")
            .build()

    override fun appended(
        duration: Duration,
        lockWait: Duration,
    ) {
        this.duration.record(duration.toNanos() / NANOS_PER_SECOND)
        this.lockWait.record(lockWait.toNanos() / NANOS_PER_SECOND)
    }

    override fun failed(error: AuditError) {
        failures.add(1, Attributes.of(ERROR_CODE, error.code))
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val ERROR_CODE: AttributeKey<String> = AttributeKey.stringKey("error.code")
    }
}
