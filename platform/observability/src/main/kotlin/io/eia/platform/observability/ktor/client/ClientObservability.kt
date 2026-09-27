package io.eia.platform.observability.ktor.client

import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.context.LogKeys
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.metrics.HttpExchange
import io.eia.platform.observability.metrics.HttpMetrics
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.getOrNull
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HeadersBuilder
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.semconv.ErrorAttributes
import io.opentelemetry.semconv.HttpAttributes
import io.opentelemetry.semconv.ServerAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.TimeSource

/**
 * 送信側の可観測性(Framework 14.1。ADR-0018 §2)。
 *
 * - 呼び出し元のコルーチンの [ObservabilityContext](なければ OTel の現在の Context)を親として CLIENT span を作り、
 *   `traceparent` / `tracestate` を注入する。
 * - `X-Correlation-Id` を付ける。呼び出し側が明示的に付けた正しい値はそのまま使い、span の属性にも同じ値を入れる。
 *   明示的な値が不正なら、コンテキストの値に置き換える(コンテキストもなければ送らない)。
 * - 呼び出し側のキャンセルは、応答がないのでステータスを記録せず、エラーにも数えない。
 *   タイムアウト(`withTimeout` の期限切れ)は `error.type=timeout` でエラーに数える(ADR-0018 §5)。
 * - RED メトリクス([HttpMetrics])を記録する。URL のパスとクエリは属性に入れない(個人情報やトークンを含みうるため)。
 */
public val ClientObservability: ClientPlugin<ClientObservabilityConfig> =
    createClientPlugin("EiaClientObservability", ::ClientObservabilityConfig) {
        val runtime = requireNotNull(pluginConfig.runtime) { "ClientObservability には runtime(ObservabilityRuntime)が必要です" }
        val propagator = runtime.openTelemetry.propagators.textMapPropagator
        val metrics = HttpMetrics(runtime.meter)

        on(Send) { request ->
            val observability = currentCoroutineContext()[ObservabilityContext]
            val parent = observability?.otelContext ?: Context.current()
            val method = HttpMetrics.normalizeMethod(request.method.value)
            val span =
                runtime.tracer
                    .spanBuilder(method)
                    .setSpanKind(SpanKind.CLIENT)
                    .setParent(parent)
                    .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, method)
                    .setAttribute(ServerAttributes.SERVER_ADDRESS, request.url.host)
                    .setAttribute(ServerAttributes.SERVER_PORT, request.url.port.toLong())
                    .startSpan()
            propagator.inject(parent.with(span), request, RequestSetter)
            val correlationId = correlationIdFor(request, observability)
            request.headers.remove(CorrelationHeaders.X_CORRELATION_ID)
            correlationId?.let {
                request.headers.append(CorrelationHeaders.X_CORRELATION_ID, correlationId.value)
                span.setAttribute(CORRELATION_ID, correlationId.value)
            }

            observe(request.url.host, method, span, metrics) { proceed(request) }
        }
    }

/** 例外を記録して再送出し、最後に span とメトリクスを閉じる。 */
@Suppress("TooGenericExceptionCaught") // 例外は握りつぶさず、記録してから再送出する
private suspend fun <T : HttpClientCall> observe(
    host: String,
    method: String,
    span: Span,
    metrics: HttpMetrics,
    send: suspend () -> T,
): T {
    val started = TimeSource.Monotonic.markNow()
    var status: Int? = null
    var failure: Throwable? = null
    try {
        return send().also { status = it.response.status.value }
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        val exchange = HttpExchange(method, status, HttpMetrics.clientErrorType(status, failure))
        status?.let { span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, it.toLong()) }
        exchange.errorType?.let {
            span.setAttribute(ErrorAttributes.ERROR_TYPE, it)
            span.setStatus(StatusCode.ERROR)
        }
        span.end()
        metrics.recordClient(started.elapsedNow(), exchange, host)
    }
}

private val CORRELATION_ID = AttributeKey.stringKey(LogKeys.CORRELATION_ID)

/** 送る Correlation ID。明示的に付けた正しい値を優先し、なければ(不正なら)コンテキストの値。どちらもなければ `null`。 */
private fun correlationIdFor(
    request: HttpRequestBuilder,
    observability: ObservabilityContext?,
): CorrelationId? {
    val explicit =
        request.headers.getAll(CorrelationHeaders.X_CORRELATION_ID)?.singleOrNull()?.let {
            CorrelationId.parse(it.trim()).getOrNull()
        }
    return explicit ?: observability?.correlationId
}

private object RequestSetter : TextMapSetter<HttpRequestBuilder> {
    override fun set(
        carrier: HttpRequestBuilder?,
        key: String,
        value: String,
    ) {
        carrier?.headers?.replace(key, value)
    }

    private fun HeadersBuilder.replace(
        key: String,
        value: String,
    ) {
        remove(key)
        append(key, value)
    }
}
