package io.eia.platform.observability

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ObservabilityConfigSpec :
    FunSpec({
        test("環境変数から作る。送り先の末尾の / は落とし、未指定の項目は既定値にする") {
            val config =
                ObservabilityConfig
                    .fromEnvironment(
                        mapOf(
                            "OTEL_SERVICE_NAME" to "order-service",
                            "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://localhost:19318/",
                            "OTEL_TRACES_SAMPLER_ARG" to "0.25",
                        ),
                    ).ok()

            config.serviceName shouldBe "order-service"
            config.otlpEndpoint shouldBe "http://localhost:19318"
            config.samplingRatio shouldBe 0.25
            config.environment shouldBe "local"
            ObservabilityConfig.fromEnvironment(mapOf("OTEL_SERVICE_NAME" to "s", "EIA_ENVIRONMENT" to "ci")).ok().let {
                it.environment shouldBe "ci"
                it.otlpEndpoint shouldBe null
                it.samplingRatio shouldBe 1.0
            }
        }

        test("不正な値はまとめて検証エラーにし、値そのものはメッセージに含めない") {
            val result = ObservabilityConfig.of(serviceName = " ", otlpEndpoint = "ftp://secret-host", samplingRatio = 1.5)
            val error = result.shouldBeInstanceOf<Result.Err<*>>().error.toString()

            listOf("serviceName", "otlpEndpoint", "samplingRatio").forEach { error.contains(it) shouldBe true }
            error shouldNotContain "secret-host"
            ObservabilityConfig
                .fromEnvironment(mapOf("OTEL_SERVICE_NAME" to "s", "OTEL_TRACES_SAMPLER_ARG" to "half"))
                .shouldBeInstanceOf<Result.Err<*>>()
            ObservabilityConfig.of("s", samplingRatio = Double.NaN).shouldBeInstanceOf<Result.Err<*>>()
        }

        test("送り先がなければ OTLP には何も送らない") {
            TelemetrySinks.otlp(null).let {
                it.spanProcessors shouldBe emptyList()
                it.metricReaders shouldBe emptyList()
                it.logProcessors shouldBe emptyList()
            }
            TelemetrySinks.otlp("http://localhost:19318").spanProcessors.size shouldBe 1
        }

        test("初期化した OTel は Propagator に EIAF の実装だけを持ち、リソースにサービス名を入れる") {
            TestTelemetry(serviceName = "inventory-service").use { telemetry ->
                telemetry.runtime.openTelemetry.propagators.textMapPropagator
                    .fields() shouldBe listOf("traceparent", "tracestate")
                telemetry.runtime.tracer
                    .spanBuilder("x")
                    .startSpan()
                    .end()

                telemetry.spans
                    .single()
                    .resource
                    .attributes
                    .get(io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME) shouldBe "inventory-service"
            }
        }
    })
