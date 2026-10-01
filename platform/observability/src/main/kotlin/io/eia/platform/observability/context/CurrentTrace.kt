package io.eia.platform.observability.context

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.trace.SpanId
import io.eia.shared.resilience.trace.TraceFlags
import io.eia.shared.resilience.trace.TraceId
import io.eia.shared.resilience.trace.TraceParent
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * 今の処理(受信したリクエストなど)の Correlation ID と、今の span の `traceparent`。
 * 監査の記録(ADR-0017 §8 の A17-6)など、処理の外に残す記録に、追跡の値を入れるために使う。
 *
 * @property correlationId [ObservabilityContext] がなければ null
 * @property traceParent 今の span が有効でなければ null
 */
public data class CurrentTrace(
    public val correlationId: CorrelationId?,
    public val traceParent: TraceParent?,
) {
    public companion object {
        /** 今のコルーチンの [ObservabilityContext] から読む。`ServerObservability` の内側の処理なら、どちらも入る。 */
        public suspend fun get(): CurrentTrace {
            val context = currentCoroutineContext()[ObservabilityContext]
            val span = context?.let { Span.fromContext(it.otelContext).spanContext }?.takeIf { it.isValid }
            return CurrentTrace(context?.correlationId, span?.toTraceParent())
        }
    }
}

private const val BYTE_MASK = 0xff

/** OTel の [SpanContext] を `traceparent` にする(伝搬の Propagator と同じ変換)。 */
internal fun SpanContext.toTraceParent(): TraceParent? {
    val trace = (TraceId.parse(traceId) as? Result.Ok)?.value
    val span = (SpanId.parse(spanId) as? Result.Ok)?.value
    val flags = (TraceFlags.of(traceFlags.asByte().toInt() and BYTE_MASK) as? Result.Ok)?.value
    return if (trace != null && span != null && flags != null) TraceParent(trace, span, flags) else null
}
