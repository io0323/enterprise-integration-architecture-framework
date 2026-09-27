package io.eia.platform.observability

import io.eia.platform.observability.logging.OtlpLogAppender
import io.eia.platform.observability.propagation.EiaTraceContextPropagator
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.semconv.ServiceAttributes
import java.util.concurrent.TimeUnit

/** テレメトリの出力先。既定は OTLP/HTTP([otlp])。テストでは InMemory の exporter / reader を渡す。 */
public class TelemetrySinks(
    public val spanProcessors: List<SpanProcessor> = emptyList(),
    public val metricReaders: List<MetricReader> = emptyList(),
    public val logProcessors: List<LogRecordProcessor> = emptyList(),
) {
    public companion object {
        private const val METRIC_INTERVAL_SECONDS = 10L

        /** OTLP/HTTP の `{endpoint}/v1/{traces,metrics,logs}` に送る。[endpoint] が `null` なら何も送らない。 */
        public fun otlp(endpoint: String?): TelemetrySinks {
            if (endpoint == null) return TelemetrySinks()
            return TelemetrySinks(
                spanProcessors =
                    listOf(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint("$endpoint/v1/traces").build()).build()),
                metricReaders =
                    listOf(
                        PeriodicMetricReader
                            .builder(OtlpHttpMetricExporter.builder().setEndpoint("$endpoint/v1/metrics").build())
                            .setInterval(METRIC_INTERVAL_SECONDS, TimeUnit.SECONDS)
                            .build(),
                    ),
                logProcessors =
                    listOf(
                        BatchLogRecordProcessor
                            .builder(
                                OtlpHttpLogRecordExporter.builder().setEndpoint("$endpoint/v1/logs").build(),
                            ).build(),
                    ),
            )
        }
    }
}

/**
 * 初期化した OTel。Ktor のプラグインへは Koin などで明示的に渡す(`GlobalOpenTelemetry` には登録しない。ADR-0018 §1)。
 * 終了時に [close] で未送信のテレメトリを送り切る。
 */
public class ObservabilityRuntime internal constructor(
    public val config: ObservabilityConfig,
    // SDK の型は公開しない。ほかのモジュールは OTel API の型([openTelemetry] / [tracer] / [meter])だけを使う(ADR-0004 §4)
    internal val sdk: OpenTelemetrySdk,
) : AutoCloseable {
    public val openTelemetry: OpenTelemetry get() = sdk
    public val tracer: Tracer = sdk.getTracer(INSTRUMENTATION_SCOPE)
    public val meter: Meter = sdk.getMeter(INSTRUMENTATION_SCOPE)

    /** 溜まっているテレメトリを送る(テストや終了前に使う)。 */
    public fun flush(timeoutSeconds: Long = FLUSH_TIMEOUT_SECONDS) {
        sdk.sdkTracerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        sdk.sdkMeterProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        sdk.sdkLoggerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
    }

    override fun close() {
        flush()
        sdk.shutdown().join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    public companion object {
        /** span と metric の計装スコープ名。 */
        public const val INSTRUMENTATION_SCOPE: String = "io.eia.platform.observability"
        private const val FLUSH_TIMEOUT_SECONDS = 10L
    }
}

/** OTel の初期化(Framework 14。ADR-0018)。 */
public object Observability {
    private val DEPLOYMENT_ENVIRONMENT = AttributeKey.stringKey("deployment.environment.name")

    /**
     * OTel SDK を組み立てる。Propagator は [EiaTraceContextPropagator] だけ(Baggage は使わない。ADR-0018 §2)。
     * logback に [OtlpLogAppender] が付いていれば、初期化前に溜めたログを送る。
     */
    public fun init(
        config: ObservabilityConfig,
        sinks: TelemetrySinks = TelemetrySinks.otlp(config.otlpEndpoint),
        installLogAppender: Boolean = true,
    ): ObservabilityRuntime {
        val resource =
            Resource.getDefault().merge(
                Resource.create(
                    Attributes.of(ServiceAttributes.SERVICE_NAME, config.serviceName, DEPLOYMENT_ENVIRONMENT, config.environment),
                ),
            )
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(config.samplingRatio)))
                .apply { sinks.spanProcessors.forEach(::addSpanProcessor) }
                .build()
        val meterProvider =
            SdkMeterProvider
                .builder()
                .setResource(resource)
                .apply { sinks.metricReaders.forEach(::registerMetricReader) }
                .build()
        val loggerProvider =
            SdkLoggerProvider
                .builder()
                .setResource(resource)
                .apply { sinks.logProcessors.forEach(::addLogRecordProcessor) }
                .build()
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setLoggerProvider(loggerProvider)
                .setPropagators(ContextPropagators.create(EiaTraceContextPropagator))
                .build()
        if (installLogAppender) OtlpLogAppender.install(loggerProvider)
        return ObservabilityRuntime(config, sdk)
    }
}
