package io.eia.platform.observability.context

import io.eia.shared.kernel.CorrelationId
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import kotlin.coroutines.CoroutineContext

/** ログの MDC のキー(CODING_STANDARDS「ロギング」の必須キー)。 */
public object LogKeys {
    public const val TRACE_ID: String = "trace_id"
    public const val SPAN_ID: String = "span_id"
    public const val CORRELATION_ID: String = "correlation_id"
    public const val INTEGRATION_ID: String = "integration_id"

    internal val ALL: List<String> = listOf(TRACE_ID, SPAN_ID, CORRELATION_ID, INTEGRATION_ID)
}

/**
 * 1 つの処理(受信したリクエストなど)の Correlation ID・連携 ID・OTel の Context を運ぶコルーチンのコンテキスト要素(ADR-0018 §2)。
 *
 * コルーチンがスレッドで再開するたびに、MDC([LogKeys])と OTel の現在の Context を設定し、中断したら元に戻す。
 * そのため `withContext(Dispatchers.IO)` などでスレッドが変わっても、ログと span の親が途切れない。
 * 途中で値を変えるときは MDC を直接書かず、`withContext(context.copy(...))` で新しい要素を入れる。
 */
public data class ObservabilityContext(
    public val correlationId: CorrelationId,
    public val integrationId: String? = null,
    public val otelContext: Context = Context.root(),
) : ThreadContextElement<ObservabilityContext.Saved> {
    public companion object Key : CoroutineContext.Key<ObservabilityContext>

    override val key: CoroutineContext.Key<ObservabilityContext> get() = Key

    /** 再開前のスレッドの状態。[restoreThreadContext] で戻す。 */
    public class Saved internal constructor(
        internal val mdc: Map<String, String?>,
        internal val scope: Scope,
    )

    override fun updateThreadContext(context: CoroutineContext): Saved {
        val saved = LogKeys.ALL.associateWith { MDC.get(it) }
        val spanContext = Span.fromContext(otelContext).spanContext
        if (spanContext.isValid) {
            MDC.put(LogKeys.TRACE_ID, spanContext.traceId)
            MDC.put(LogKeys.SPAN_ID, spanContext.spanId)
        } else {
            MDC.remove(LogKeys.TRACE_ID)
            MDC.remove(LogKeys.SPAN_ID)
        }
        MDC.put(LogKeys.CORRELATION_ID, correlationId.value)
        if (integrationId != null) MDC.put(LogKeys.INTEGRATION_ID, integrationId) else MDC.remove(LogKeys.INTEGRATION_ID)
        return Saved(saved, otelContext.makeCurrent())
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Saved,
    ) {
        oldState.scope.close()
        oldState.mdc.forEach { (key, value) -> if (value == null) MDC.remove(key) else MDC.put(key, value) }
    }
}
