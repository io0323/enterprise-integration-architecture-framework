package io.eia.platform.observability.ktor.server

import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.context.LogKeys
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.metrics.HttpExchange
import io.eia.platform.observability.metrics.HttpMetrics
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.fold
import io.ktor.http.Headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.semconv.ErrorAttributes
import io.opentelemetry.semconv.HttpAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 受信側の可観測性(Framework 14.1。ADR-0018 §2)。
 *
 * - `traceparent` / `tracestate` を [io.eia.platform.observability.propagation.EiaTraceContextPropagator] で読み、SERVER span を作る。
 *   不正な値は捨てて新しいトレースを始める(受信した値はログに出さない)。
 * - `X-Correlation-Id` は受信した値を使い、ない・不正なら入口で採番する(Framework 14.1)。レスポンスのヘッダにも返す。
 *   ログの MDC(`correlation_id`)と span の属性(`correlation_id`)の両方に入れる。
 * - 以降の処理は [ObservabilityContext] の中で動くため、スレッドが変わっても MDC と span の親が保たれる。
 * - RED メトリクス([HttpMetrics])を記録する。リクエストとレスポンスの本文は読まず、ログにも出さない。
 * - 未処理の例外は、Correlation ID と trace_id の付いたログに 1 回だけ記録する(Ktor 自身のログはそれらの外で出るため)。
 * - 処理のキャンセル(クライアントの切断など)は、応答していないのでステータスを記録せず、エラーにも数えない。
 *   タイムアウトは `error.type=timeout` でエラーに数え、Ktor が返す 504 を記録し、WARN を 1 回残す(ADR-0018 §5)。
 */
public val ServerObservability: ApplicationPlugin<ServerObservabilityConfig> =
    createApplicationPlugin("EiaServerObservability", ::ServerObservabilityConfig) {
        val runtime = requireNotNull(pluginConfig.runtime) { "ServerObservability には runtime(ObservabilityRuntime)が必要です" }
        val integrationId = pluginConfig.integrationId
        val propagator = runtime.openTelemetry.propagators.textMapPropagator
        val metrics = HttpMetrics(runtime.meter)

        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            if (call.attributes.contains(ROUTE)) return@subscribe
            val route = routeTemplate(call.route)
            call.attributes.put(ROUTE, route)
            call.attributes.getOrNull(IN_FLIGHT)?.span?.let { span ->
                span.setAttribute(HttpAttributes.HTTP_ROUTE, route)
                span.updateName("${HttpMetrics.normalizeMethod(call.request.local.method.value)} $route")
            }
        }

        application.intercept(ApplicationCallPipeline.Monitoring) {
            val method = HttpMetrics.normalizeMethod(call.request.local.method.value)
            val parent = propagator.extract(Context.root(), call.request.headers, HeadersGetter)
            val span =
                runtime.tracer
                    .spanBuilder(method)
                    .setSpanKind(SpanKind.SERVER)
                    .setParent(parent)
                    .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, method)
                    .startSpan()
            val inFlight = InFlight(span, TimeSource.Monotonic.markNow(), method, integrationId)
            call.attributes.put(IN_FLIGHT, inFlight)

            val received = receivedCorrelationId(call.request.headers)
            val correlationId = (received as? Received.Valid)?.id ?: CorrelationId.generate()
            span.setAttribute(CORRELATION_ID, correlationId.value)
            integrationId?.let { span.setAttribute(HttpMetrics.INTEGRATION_ID, it) }
            call.response.headers.append(CorrelationHeaders.X_CORRELATION_ID, correlationId.value)

            withContext(ObservabilityContext(correlationId, integrationId, parent.with(span))) {
                if (received is Received.Invalid) {
                    // 外部から大量に送られてもログが増えないよう DEBUG にし、件数はメトリクスで見る
                    logger.debug("受信した {} が不正なため、新しく採番しました", CorrelationHeaders.X_CORRELATION_ID)
                    metrics.recordInvalidCorrelationId(integrationId)
                }
                observe(call, inFlight, metrics) { proceed() }
            }
        }
    }

private val logger = LoggerFactory.getLogger("io.eia.platform.observability.ktor.server.ServerObservability")
private val IN_FLIGHT = AttributeKey<InFlight>("eia.observability.in-flight")
private val ROUTE = AttributeKey<String>("eia.observability.route")
private val CORRELATION_ID = stringKey(LogKeys.CORRELATION_ID)
private const val SERVER_ERROR = 500
private const val GATEWAY_TIMEOUT = 504

/** 処理中のリクエスト(終了時にメトリクスと span を閉じるための情報)。 */
private class InFlight(
    val span: Span,
    val started: TimeMark,
    val method: String,
    val integrationId: String?,
)

/** 例外を記録して再送出し、最後に span とメトリクスを閉じる。 */
@Suppress("TooGenericExceptionCaught") // 例外は握りつぶさず、記録してから再送出する
private suspend fun observe(
    call: ApplicationCall,
    inFlight: InFlight,
    metrics: HttpMetrics,
    proceed: suspend () -> Unit,
) {
    var failure: Throwable? = null
    try {
        proceed()
    } catch (e: Throwable) {
        failure = e
        // Ktor もこの後に応答・記録するが、それは Correlation ID と trace_id の外になる。追跡できるようにここで 1 回記録する
        when (e) {
            // Ktor は 504 を返す。どの処理が期限切れになったかを追えるようにする
            is TimeoutCancellationException -> logger.warn("処理がタイムアウトしました(error.type={})", HttpMetrics.TIMEOUT)

            // クライアントの切断など。応答しないので記録しない
            is CancellationException -> Unit

            else -> logger.error("未処理の例外で 500 を返します(error.type={})", HttpMetrics.errorTypeOf(e), e)
        }
        throw e
    } finally {
        complete(call, inFlight, metrics, failure)
    }
}

private fun complete(
    call: ApplicationCall,
    inFlight: InFlight,
    metrics: HttpMetrics,
    failure: Throwable?,
) {
    // 例外が外まで伝わった場合、Ktor はこの後に応答する: タイムアウトは 504、それ以外の例外は 500。
    // キャンセル(クライアントの切断など)は応答しないのでステータスはない(エラーにもしない。HttpMetrics.failureErrorType)
    val status =
        call.response.status()?.value ?: when (failure) {
            null -> null
            is TimeoutCancellationException -> GATEWAY_TIMEOUT
            is CancellationException -> null
            else -> SERVER_ERROR
        }
    val exchange = HttpExchange(inFlight.method, status, HttpMetrics.serverErrorType(status, failure))
    val span = inFlight.span
    status?.let { span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, it.toLong()) }
    exchange.errorType?.let {
        span.setAttribute(ErrorAttributes.ERROR_TYPE, it)
        span.setStatus(StatusCode.ERROR)
    }
    // 例外のメッセージは個人情報を含みうるため、span のイベント(recordException)には入れず、型だけを error.type に残す
    span.end()
    metrics.recordServer(inFlight.started.elapsedNow(), exchange, call.attributes.getOrNull(ROUTE), inFlight.integrationId)
}

private sealed interface Received {
    data class Valid(
        val id: CorrelationId,
    ) : Received

    data object Invalid : Received

    data object Absent : Received
}

/** 1 つだけ受信し、形式が正しい場合に使う。複数あるときも不正とする(どれが正しいか決められないため)。 */
private fun receivedCorrelationId(headers: Headers): Received {
    val values = headers.getAll(CorrelationHeaders.X_CORRELATION_ID)
    return when {
        values == null -> Received.Absent
        values.size != 1 -> Received.Invalid
        else -> CorrelationId.parse(values.single().trim()).fold({ Received.Valid(it) }, { Received.Invalid })
    }
}

/** `/v1/orders/{id}/(method:GET)` のような Ktor のルートから、メソッドなどの条件を除いたテンプレート(`/v1/orders/{id}`)を作る。 */
internal fun routeTemplate(route: RoutingNode): String {
    val path = route.toString().replace(CONDITION_SEGMENT, "").replace(REPEATED_SLASH, "/")
    return path.ifEmpty { "/" }
}

private val CONDITION_SEGMENT = Regex("""/\([^)]*\)""")
private val REPEATED_SLASH = Regex("/{2,}")

private object HeadersGetter : TextMapGetter<Headers> {
    override fun keys(carrier: Headers): Iterable<String> = carrier.names()

    /**
     * W3C Trace Context §3.3: tracestate は複数行で送られることがあり、`,` で連結して 1 つの値として扱う。
     * traceparent が複数行ある場合も連結され、不正な値として捨てられる。
     */
    override fun get(
        carrier: Headers?,
        key: String,
    ): String? = carrier?.getAll(key)?.joinToString(",")
}
