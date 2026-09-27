package io.eia.platform.observability

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

internal fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

/** InMemory の exporter / reader で OTel を初期化する(テスト用)。 */
internal class TestTelemetry(
    serviceName: String = "test-service",
    installLogAppender: Boolean = true,
) : AutoCloseable {
    private val spanExporter = InMemorySpanExporter.create()
    private val metricReader = InMemoryMetricReader.create()
    private val logExporter = InMemoryLogRecordExporter.create()

    val runtime: ObservabilityRuntime =
        Observability.init(
            ObservabilityConfig.of(serviceName).ok(),
            TelemetrySinks(
                spanProcessors = listOf(SimpleSpanProcessor.create(spanExporter)),
                metricReaders = listOf(metricReader),
                logProcessors = listOf(SimpleLogRecordProcessor.create(logExporter)),
            ),
            installLogAppender = installLogAppender,
        )

    val spans: List<SpanData> get() = spanExporter.finishedSpanItems

    val logs: List<LogRecordData> get() = logExporter.finishedLogRecordItems

    fun metrics(): Collection<MetricData> = metricReader.collectAllMetrics()

    override fun close() = runtime.close()
}
