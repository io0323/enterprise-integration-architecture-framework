package io.eia.platform.grpc

import io.eia.platform.observability.context.LogKeys
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.security.jwt.JwtVerificationError
import io.eia.platform.security.tls.ClientCertificateAllowList
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.fold
import io.eia.shared.resilience.CallDeadline
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.kotlin.CoroutineContextServerInterceptor
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val CALL_CONTEXT: Context.Key<CoroutineContext> = Context.key("eia-grpc-call-context")

/** [GrpcServerInterceptor] が作ったコルーチンのコンテキスト(可観測性と締め切り)を、サービスの実装に渡す。 */
internal object CallCoroutineContext : CoroutineContextServerInterceptor() {
    override fun coroutineContext(
        call: ServerCall<*, *>,
        headers: Metadata,
    ): CoroutineContext = checkNotNull(CALL_CONTEXT.get()) { "GrpcServerInterceptor を通っていません" }
}

/**
 * すべての呼び出しに適用する処理(ADR-0042 §5〜§6)。次の順に確かめ、最初に失敗した段で閉じる。
 *
 * 1. 可観測性: `traceparent` を親に SERVER の span を始める。`x-correlation-id` は受信した値を使い、ない・不正なら採番する。応答の headers にも返す。
 * 2. クライアント証明書の SAN が許可の一覧にある。なければ `PERMISSION_DENIED`。
 * 3. トークンが正しい(なければ `UNAUTHENTICATED`。公開鍵を取得できなければ `UNAVAILABLE`)。スコープが足りる(なければ `PERMISSION_DENIED`)。
 * 4. 締め切り: 呼び出し元の deadline と [GrpcServerConfig.maxBudget] の短い方を [CallDeadline] で置く。
 *
 * トークンの検証は呼び出しの実行スレッド(イベントループではない)で待つ。`JwtVerifier` は JWKS の取得を上限つきの dispatcher で行うので、
 * ここで待っても共有の dispatcher は埋まらない。インターセプタで行うのは、サービスの実装が検証を呼び忘れられないようにするため。
 */
internal class GrpcServerInterceptor(
    private val config: GrpcServerConfig,
    private val requiredScopes: Set<String>,
    private val metrics: GrpcMetrics,
) : ServerInterceptor {
    private val telemetry = config.telemetry
    private val propagator = telemetry.openTelemetry.propagators.textMapPropagator

    override fun <Q, A> interceptCall(
        call: ServerCall<Q, A>,
        headers: Metadata,
        next: ServerCallHandler<Q, A>,
    ): ServerCall.Listener<Q> {
        val method = call.methodDescriptor.fullMethodName
        val parent =
            propagator.extract(
                io.opentelemetry.context.Context
                    .root(),
                headers,
                GrpcHeaders.GETTER,
            )
        val span =
            telemetry.tracer
                .spanBuilder(method)
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .setAttribute(GrpcMetrics.RPC_SYSTEM, "grpc")
                .setAttribute(GrpcMetrics.RPC_METHOD, method)
                .startSpan()
        val correlationId = correlationId(headers)
        span.setAttribute(CORRELATION_ID, correlationId.value)
        val observed = ObservedCall(call, span, correlationId, method, metrics)

        val rejection = rejection(call, headers)
        if (rejection != null) {
            observed.close(rejection, Metadata())
            return object : ServerCall.Listener<Q>() {}
        }
        val coroutineContext =
            ObservabilityContext(correlationId, otelContext = parent.with(span)) + CallDeadline.after(budget())
        val listener = Contexts.interceptCall(Context.current().withValue(CALL_CONTEXT, coroutineContext), observed, headers, next)
        return ObservedListener(listener, observed)
    }

    private fun correlationId(headers: Metadata): CorrelationId =
        headers
            .get(GrpcHeaders.CORRELATION_ID)
            ?.let { CorrelationId.parse(it.trim()).fold({ id -> id }, { null }) }
            ?: CorrelationId.generate()

    private fun budget(): Duration {
        val remaining =
            Context
                .current()
                .deadline
                ?.timeRemaining(TimeUnit.MILLISECONDS)
                ?.milliseconds
        return remaining?.coerceAtMost(config.maxBudget) ?: config.maxBudget
    }

    private fun rejection(
        call: ServerCall<*, *>,
        headers: Metadata,
    ): Status? {
        if (!clientAllowed(call)) {
            metrics.recordCertificateRejection(CLIENT_NOT_ALLOWED)
            // 証明書の内容(個人や組織の名前を含みうる)はログに出さない
            logger.warn("許可の一覧にないクライアント証明書の呼び出しを拒否しました")
            return Status.PERMISSION_DENIED.withDescription("client certificate is not allowed")
        }
        val authorization = headers.get(GrpcHeaders.AUTHORIZATION)
        val token = authorization?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim()
        return if (token.isNullOrEmpty()) Status.UNAUTHENTICATED.withDescription(INVALID_TOKEN) else authorize(token)
    }

    private fun authorize(token: String): Status? =
        when (val verified = runBlocking { config.verify(token) }) {
            is Result.Ok -> {
                if (verified.value.hasScopes(requiredScopes)) null else Status.PERMISSION_DENIED.withDescription("insufficient scope")
            }

            is Result.Err -> {
                // 拒否の理由は、JwtVerifier がログとメトリクスにだけ残す(呼び出し元には返さない。ADR-0019 §5)
                when (verified.error) {
                    is JwtVerificationError.InvalidToken -> Status.UNAUTHENTICATED.withDescription(INVALID_TOKEN)
                    JwtVerificationError.KeysUnavailable -> Status.UNAVAILABLE.withDescription("token keys are unavailable")
                }
            }
        }

    private fun clientAllowed(call: ServerCall<*, *>): Boolean {
        val session = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION) ?: return false
        val peer =
            try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: SSLPeerUnverifiedException) {
                null
            }
        return peer != null && ClientCertificateAllowList.dnsNames(peer).any { it in config.allowedClients }
    }

    private companion object {
        const val BEARER = "Bearer "
        const val INVALID_TOKEN = "invalid token"
        const val CLIENT_NOT_ALLOWED = "client_not_allowed"
        val CORRELATION_ID: AttributeKey<String> = AttributeKey.stringKey(LogKeys.CORRELATION_ID)
        val logger = LoggerFactory.getLogger(GrpcServerInterceptor::class.java)
    }
}

/** 応答の headers に Correlation ID を付け、呼び出しの終わりに span とメトリクスを閉じる。 */
private class ObservedCall<Q, A>(
    delegate: ServerCall<Q, A>,
    private val span: Span,
    private val correlationId: CorrelationId,
    private val method: String,
    private val metrics: GrpcMetrics,
) : ForwardingServerCall.SimpleForwardingServerCall<Q, A>(delegate) {
    private val started = TimeSource.Monotonic.markNow()
    private val finished = AtomicBoolean(false)

    override fun sendHeaders(headers: Metadata) {
        headers.put(GrpcHeaders.CORRELATION_ID, correlationId.value)
        super.sendHeaders(headers)
    }

    override fun close(
        status: Status,
        trailers: Metadata,
    ) {
        finish(status.code)
        super.close(status, trailers)
    }

    /** 呼び出し元の取り消しでは [close] が呼ばれない。応答がないので状態コードを `CANCELLED` にして、エラーには数えない。 */
    fun cancelled() = finish(Status.Code.CANCELLED)

    private fun finish(code: Status.Code) {
        if (!finished.compareAndSet(false, true)) return
        val errorType = code.name.takeIf { code != Status.Code.OK && code != Status.Code.CANCELLED }
        span.setAttribute(GrpcMetrics.RPC_STATUS, code.name)
        errorType?.let {
            span.setAttribute(GrpcMetrics.ERROR_TYPE, it)
            span.setStatus(StatusCode.ERROR)
        }
        span.end()
        metrics.recordServer(started.elapsedNow(), method, code.name, errorType)
    }
}

private class ObservedListener<Q>(
    delegate: ServerCall.Listener<Q>,
    private val observed: ObservedCall<Q, *>,
) : ForwardingServerCallListener.SimpleForwardingServerCallListener<Q>(delegate) {
    override fun onCancel() {
        observed.cancelled()
        super.onCancel()
    }
}
