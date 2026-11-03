package io.eia.platform.grpc

import io.eia.shared.kernel.DomainError
import io.eia.shared.resilience.UncountedFailure
import io.grpc.Status

/**
 * gRPC の呼び出しのエラー(ADR-0042 §7)。依存先の名前と、決まった値だけを持つ。
 * `Status.description` は受け取らない(相手が書く文で、ログや上流の応答に流さないため)。
 */
public sealed interface GrpcCallError {
    public val dependency: String
}

/** 一時的な失敗(`UNAVAILABLE`・`DEADLINE_EXCEEDED`・`RESOURCE_EXHAUSTED`・`ABORTED`)。再試行してよく、Circuit Breaker の失敗に数える。 */
public data class GrpcCallUnavailable(
    override val dependency: String,
    public val status: Status.Code,
) : GrpcCallError,
    DomainError.Retryable {
    override val code: String get() = "grpc_${status.name.lowercase()}"
    override val message: String get() = "$dependency の gRPC の呼び出しが $status で失敗しました"
}

/** 恒久的な失敗(`INVALID_ARGUMENT`・`PERMISSION_DENIED`・`INTERNAL` など)。再試行しない。 */
public data class GrpcCallRejected(
    override val dependency: String,
    public val status: Status.Code,
) : GrpcCallError,
    DomainError.NonRetryable {
    override val code: String get() = "grpc_${status.name.lowercase()}"
    override val message: String get() = "$dependency の gRPC の呼び出しが $status で拒否されました"
}

/**
 * 証明書が原因で接続できない(ADR-0042 §8)。gRPC の状態コードは `UNAVAILABLE` だが、再試行しても直らず、依存先の障害でもないので、
 * 再試行せず、Circuit Breaker にも数えない。
 */
public data class GrpcCertificateFailure(
    override val dependency: String,
    public val reason: CertificateFailureReason,
) : GrpcCallError,
    DomainError.NonRetryable,
    UncountedFailure {
    override val code: String get() = "grpc_certificate_${reason.label}"
    override val message: String get() = "$dependency への gRPC の接続が証明書のエラーで失敗しました(${reason.label})"
}

/** 相手か途中の経路が呼び出しを取り消した(`CANCELLED`)。依存先の状態が分からないので、Circuit Breaker に数えない。 */
public data class GrpcCallCancelled(
    override val dependency: String,
) : GrpcCallError,
    DomainError.NonRetryable,
    UncountedFailure {
    override val code: String get() = "grpc_cancelled"
    override val message: String get() = "$dependency の gRPC の呼び出しが取り消されました"
}

/** 状態コードを [DomainError] に写す(ADR-0042 §7)。`UNAUTHENTICATED` のトークンの取り直しは [GrpcClient] が先に行う。 */
internal fun Status.toDomainError(dependency: String): DomainError {
    val certificate = if (code == Status.Code.UNAVAILABLE) CertificateFailures.classify(cause) else null
    return when {
        certificate != null -> GrpcCertificateFailure(dependency, certificate)
        code == Status.Code.CANCELLED -> GrpcCallCancelled(dependency)
        code in RETRYABLE -> GrpcCallUnavailable(dependency, code)
        else -> GrpcCallRejected(dependency, code)
    }
}

private val RETRYABLE =
    setOf(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED, Status.Code.ABORTED)
