package io.eia.platform.observability.context

import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.metrics.HttpMetrics
import io.eia.shared.kernel.CorrelationId
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.ErrorAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * 現在の span の子として [name] の span を作り、[block] の間それを現在の span にする(ADR-0018 §2)。
 *
 * コルーチンでは OTel の `span.makeCurrent()` を使わない。スレッドに結び付いた Scope は中断をまたげず、
 * 以降のログ・送信の親がずれるため。代わりにこの関数で [ObservabilityContext] を差し替える。
 * [ObservabilityContext] がない(入口の外で呼ばれた)場合は、Correlation ID を採番して始める。
 *
 * 例外は `error.type` と ERROR の状態を付けて再送出する。例外のメッセージは span に入れない。キャンセルはエラーにしない。
 */
@Suppress("TooGenericExceptionCaught") // 例外は握りつぶさず、記録してから再送出する
public suspend fun <T> ObservabilityRuntime.withSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    block: suspend (Span) -> T,
): T {
    val current = currentCoroutineContext()[ObservabilityContext]
    val parent = current?.otelContext ?: Context.current()
    val span =
        tracer
            .spanBuilder(name)
            .setSpanKind(kind)
            .setParent(parent)
            .startSpan()
    val context = (current ?: ObservabilityContext(CorrelationId.generate())).copy(otelContext = parent.with(span))
    try {
        return withContext(context) { block(span) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        span.setAttribute(ErrorAttributes.ERROR_TYPE, HttpMetrics.errorTypeOf(e))
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}
