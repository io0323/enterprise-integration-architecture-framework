package io.eia.platform.batch

import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.TelemetrySinks
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** 進められる時計。 */
internal class TestClock(
    var now: Instant = Instant.parse("2026-10-13T16:00:00Z"),
) : Clock {
    override fun now(): Instant = now

    fun advance(duration: Duration) {
        now += duration
    }
}

/** span とメトリクスをメモリに集める OTel。 */
internal class Telemetry {
    val spans: InMemorySpanExporter = InMemorySpanExporter.create()
    val metrics: InMemoryMetricReader = InMemoryMetricReader.create()
    val runtime: ObservabilityRuntime =
        Observability.init(
            ObservabilityConfig.of("test-batch").ok(),
            TelemetrySinks(spanProcessors = listOf(SimpleSpanProcessor.create(spans)), metricReaders = listOf(metrics)),
            installLogAppender = false,
        )

    /** カウンタの値(属性の値を , でつないだもの → 値)。 */
    fun counter(name: String): Map<String, Long> =
        metrics
            .collectAllMetrics()
            .filter { it.name == name }
            .flatMap { it.longSumData.points }
            .associate { point ->
                point.attributes
                    .asMap()
                    .values
                    .joinToString() to point.value
            }

    fun gauge(name: String): Map<String, Double> =
        metrics
            .collectAllMetrics()
            .filter { it.name == name }
            .flatMap { it.doubleGaugeData.points }
            .associate { point ->
                point.attributes
                    .asMap()
                    .values
                    .joinToString() to point.value
            }
}

/** テストのリトライ(待たない)。 */
internal val FAST_RETRY = RetryPolicy(initialDelay = 1.milliseconds, maxDelay = 1.milliseconds)

internal fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> error("Ok を期待しましたが Err でした: $error")
    }

internal fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> error("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }
