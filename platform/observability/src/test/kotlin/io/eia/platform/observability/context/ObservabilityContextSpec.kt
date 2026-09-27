package io.eia.platform.observability.context

import io.eia.platform.observability.ok
import io.eia.shared.kernel.CorrelationId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.MDC

private fun observed(
    correlationId: String,
    traceId: String? = null,
): ObservabilityContext {
    val otel =
        traceId?.let {
            Context.root().with(Span.wrap(SpanContext.create(it, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault())))
        } ?: Context.root()
    return ObservabilityContext(CorrelationId.parse(correlationId).ok(), "INT-1", otel)
}

class ObservabilityContextSpec :
    FunSpec({
        test("並行するコルーチンは、それぞれの MDC と OTel の Context を見る") {
            val seen =
                coroutineScope {
                    (1..20)
                        .map { n ->
                            async(Dispatchers.IO + observed("c-$n", "%032x".format(n))) {
                                withContext(Dispatchers.Default) {
                                    Triple(MDC.get(LogKeys.CORRELATION_ID), MDC.get(LogKeys.TRACE_ID), Span.current().spanContext.traceId)
                                }
                            }
                        }.awaitAll()
                }

            seen shouldBe (1..20).map { Triple("c-$it", "%032x".format(it), "%032x".format(it)) }
        }

        test("入れ子にすると内側の値になり、抜けると外側の値とスレッドの元の MDC に戻る") {
            MDC.put(LogKeys.CORRELATION_ID, "thread-original")
            try {
                withContext(observed("outer", "4bf92f3577b34da6a3ce929d0e0e4736")) {
                    withContext(observed("inner")) {
                        MDC.get(LogKeys.CORRELATION_ID) shouldBe "inner"
                        MDC.get(LogKeys.TRACE_ID) shouldBe null
                        MDC.get(LogKeys.INTEGRATION_ID) shouldBe "INT-1"
                    }
                    MDC.get(LogKeys.CORRELATION_ID) shouldBe "outer"
                    MDC.get(LogKeys.TRACE_ID) shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
                }
                MDC.get(LogKeys.CORRELATION_ID) shouldBe "thread-original"
                MDC.get(LogKeys.TRACE_ID) shouldBe null
                Span.current().spanContext.isValid shouldBe false
            } finally {
                MDC.clear()
            }
        }

        test("integration_id がなければ MDC から消す") {
            withContext(ObservabilityContext(CorrelationId.parse("c").ok())) {
                MDC.get(LogKeys.INTEGRATION_ID) shouldBe null
                MDC.get(LogKeys.CORRELATION_ID) shouldBe "c"
            }
        }
    })
