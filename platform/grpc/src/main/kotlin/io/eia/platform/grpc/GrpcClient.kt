package io.eia.platform.grpc

import io.eia.platform.observability.context.LogKeys
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.metrics.HttpMetrics
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.CallDeadline
import io.eia.shared.sdk.http.token.AccessToken
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.AbstractStub
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 呼び出しに付けるアクセストークン。[invalidate] は相手が `UNAUTHENTICATED` で拒否したときに呼ぶ。 */
public interface GrpcTokenSource {
    public suspend fun token(): Result<AccessToken, DomainError>

    public suspend fun invalidate(token: AccessToken)
}

/** Client Credentials のトークンで呼び出す(ADR-0019 §4)。 */
public fun ClientCredentialsTokenProvider.asGrpcTokenSource(): GrpcTokenSource =
    object : GrpcTokenSource {
        override suspend fun token(): Result<AccessToken, DomainError> =
            when (val token = this@asGrpcTokenSource.token()) {
                is Result.Ok -> ok(token.value)
                is Result.Err -> err(token.error.asDomainError())
            }

        override suspend fun invalidate(token: AccessToken) = this@asGrpcTokenSource.invalidate(token)
    }

/**
 * 1 つの依存先への gRPC の呼び出し(ADR-0042 §6〜§8)。`Resilience` の中で [call] を呼ぶ。
 *
 * - **締め切り**: 呼び出し元の [CallDeadline](`Resilience` が試行ごとに置く)の残り時間を、gRPC の deadline にする。
 *   [DEADLINE_GRACE] だけ長くして、打ち切りは `Resilience` に任せる。同時に切れると、`Resilience` の数え方(呼び出し元の締め切りは数えない。
 *   ADR-0021 §12)が、到着の順で変わるため。[CallDeadline] がなければ [fallbackDeadline] を使う。残り時間がなければ送らない。
 * - **伝搬**: `traceparent` と `x-correlation-id` を metadata に入れ、CLIENT の span と RED のメトリクスを記録する。
 * - **認証**: `authorization` にトークンを入れる。`UNAUTHENTICATED` なら、トークンを捨てて 1 回だけ送り直す。
 * - **エラー**: 状態コードを [DomainError] に写す。証明書のエラーは別のメトリクスに数える(ADR-0042 §8)。
 */
public class GrpcClient(
    private val dependency: String,
    private val tokens: GrpcTokenSource,
    private val telemetry: GrpcTelemetry,
    private val fallbackDeadline: Duration = 3.seconds,
) {
    private val metrics = GrpcMetrics(telemetry.meter)
    private val propagator = telemetry.openTelemetry.propagators.textMapPropagator

    /** [method] はスタブの記述子(`XxxGrpc.getYyyMethod()`)。[block] には、そのまま RPC に渡す metadata を渡す。 */
    public suspend fun <S : AbstractStub<S>, R> call(
        stub: S,
        method: MethodDescriptor<*, *>,
        block: suspend S.(Metadata) -> R,
    ): Result<R, DomainError> {
        val observability = currentCoroutineContext()[ObservabilityContext]
        val parent = observability?.otelContext ?: Context.current()
        val span =
            telemetry.tracer
                .spanBuilder(method.fullMethodName)
                .setSpanKind(SpanKind.CLIENT)
                .setParent(parent)
                .setAttribute(GrpcMetrics.RPC_SYSTEM, "grpc")
                .setAttribute(GrpcMetrics.RPC_METHOD, method.fullMethodName)
                .startSpan()
        val headers = Metadata()
        propagator.inject(parent.with(span), headers, GrpcHeaders.SETTER)
        observability?.correlationId?.let {
            headers.put(GrpcHeaders.CORRELATION_ID, it.value)
            span.setAttribute(CORRELATION_ID, it.value)
        }
        return observed(method.fullMethodName, span) { attempt(stub, headers, block, mayRenewToken = true) }
    }

    @Suppress("ReturnCount") // 送らない場合(残り時間・トークン)と、送った結果のそれぞれで返す
    private suspend fun <S : AbstractStub<S>, R> attempt(
        stub: S,
        headers: Metadata,
        block: suspend S.(Metadata) -> R,
        mayRenewToken: Boolean,
    ): Sent<R> {
        val remaining = CallDeadline.current()?.remaining() ?: fallbackDeadline
        if (!remaining.isPositive()) {
            return Sent(err(GrpcCallUnavailable(dependency, Status.Code.DEADLINE_EXCEEDED)), Status.Code.DEADLINE_EXCEEDED)
        }
        val token =
            when (val result = tokens.token()) {
                is Result.Ok -> result.value
                is Result.Err -> return Sent(err(result.error), null)
            }
        val metadata = Metadata().apply { merge(headers) }
        metadata.put(GrpcHeaders.AUTHORIZATION, token.authorizationHeader())
        val status =
            try {
                val deadline = (remaining + DEADLINE_GRACE).inWholeMilliseconds
                return Sent(ok(stub.withDeadlineAfter(deadline, TimeUnit.MILLISECONDS).block(metadata)), Status.Code.OK)
            } catch (e: StatusException) {
                e.status
            } catch (e: StatusRuntimeException) {
                e.status
            }
        if (status.code == Status.Code.UNAUTHENTICATED && mayRenewToken) {
            tokens.invalidate(token)
            return attempt(stub, headers, block, mayRenewToken = false)
        }
        val error = status.toDomainError(dependency)
        if (error is GrpcCertificateFailure) metrics.recordCertificateFailure(dependency, error.reason)
        return Sent(err(error), status.code)
    }

    /** 例外を記録して再送出し、最後に span とメトリクスを閉じる。 */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // 例外は握りつぶさず、記録してから再送出する
    private suspend fun <R> observed(
        method: String,
        span: Span,
        send: suspend () -> Sent<R>,
    ): Result<R, DomainError> {
        val started = TimeSource.Monotonic.markNow()
        var status: Status.Code? = null
        var errorType: String? = null
        try {
            val sent = send()
            status = sent.status
            (sent.result as? Result.Err)?.let { errorType = it.error.code }
            return sent.result
        } catch (e: TimeoutCancellationException) {
            errorType = HttpMetrics.TIMEOUT
            throw e
        } catch (e: CancellationException) {
            throw e // 呼び出し元の取り消し。応答がないのでエラーに数えない
        } catch (e: Exception) {
            errorType = HttpMetrics.errorTypeOf(e)
            throw e
        } finally {
            status?.let { span.setAttribute(GrpcMetrics.RPC_STATUS, it.name) }
            errorType?.let {
                span.setAttribute(GrpcMetrics.ERROR_TYPE, it)
                span.setStatus(StatusCode.ERROR)
            }
            span.end()
            metrics.recordClient(started.elapsedNow(), method, dependency, status?.name, errorType)
        }
    }

    private class Sent<R>(
        val result: Result<R, DomainError>,
        val status: Status.Code?,
    )

    public companion object {
        /** gRPC の deadline に足す余裕。 */
        public val DEADLINE_GRACE: Duration = 50.milliseconds
        private val CORRELATION_ID = AttributeKey.stringKey(LogKeys.CORRELATION_ID)
    }
}
