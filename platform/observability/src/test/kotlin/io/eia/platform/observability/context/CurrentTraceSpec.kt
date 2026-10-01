package io.eia.platform.observability.context

import io.eia.shared.kernel.CorrelationId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import kotlinx.coroutines.withContext

class CurrentTraceSpec :
    FunSpec({
        test("ObservabilityContext の中では、Correlation ID と今の span の traceparent を返す") {
            val span =
                Span.wrap(
                    SpanContext.create(
                        "4bf92f3577b34da6a3ce929d0e0e4736",
                        "00f067aa0ba902b7",
                        TraceFlags.getSampled(),
                        TraceState.getDefault(),
                    ),
                )
            val correlationId = CorrelationId.generate()
            val trace =
                withContext(ObservabilityContext(correlationId, otelContext = Context.root().with(span))) { CurrentTrace.get() }

            trace.correlationId shouldBe correlationId
            trace.traceParent?.format() shouldBe "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        }

        test("ObservabilityContext の外では、どちらも null") {
            CurrentTrace.get() shouldBe CurrentTrace(null, null)
        }

        test("span がない(無効な)ときは、Correlation ID だけを返す") {
            val correlationId = CorrelationId.generate()
            withContext(ObservabilityContext(correlationId)) { CurrentTrace.get() } shouldBe CurrentTrace(correlationId, null)
        }
    })
