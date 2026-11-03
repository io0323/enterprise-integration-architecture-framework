package io.eia.platform.grpc

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.resilience.AttemptTimedOut
import io.eia.shared.resilience.CallDeadline
import io.eia.shared.resilience.DeadlineExceeded
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("io.eia.platform.grpc.GrpcResponses")

/**
 * サービスの実装の本体を、締め切りの中で実行して、結果を gRPC の応答にする(ADR-0042 §6・§7)。
 *
 * - [CallDeadline] の残り時間で打ち切り、`DEADLINE_EXCEEDED` を返す(deadline のない呼び出しにも、サーバの上限を適用する)。
 *   待ち状態の JDBC はコルーチンの打ち切りでは止まらないので、DB 側は `statement_timeout` で切る(ADR-0024 §3)。
 * - [DomainError] を状態コードに写す。`description` には決まったエラーコードだけを入れる。
 * - 想定外の例外はログに残して `INTERNAL` にする(例外の文は呼び出し元に返さない)。
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // 想定外の例外を、記録して INTERNAL にする。状態は例外で返す(gRPC の約束)
public suspend fun <T> grpcResponse(block: suspend () -> Result<T, DomainError>): T {
    val remaining = CallDeadline.current()?.remaining()
    val result =
        try {
            if (remaining == null) block() else withTimeoutOrNull(remaining) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StatusException) {
            throw e
        } catch (e: Exception) {
            logger.error("gRPC の呼び出しの処理で想定外の例外が発生しました", e)
            throw Status.INTERNAL.withDescription("internal error").asException()
        }
    return when (result) {
        null -> {
            throw Status.DEADLINE_EXCEEDED.withDescription("deadline exceeded").asException()
        }

        is Result.Ok -> {
            result.value
        }

        is Result.Err -> {
            throw result.error
                .toStatus()
                .withDescription(result.error.code)
                .asException()
        }
    }
}

internal fun DomainError.toStatus(): Status =
    when (this) {
        is ValidationError -> Status.INVALID_ARGUMENT
        is DeadlineExceeded, is AttemptTimedOut -> Status.DEADLINE_EXCEEDED
        is DomainError.Retryable -> Status.UNAVAILABLE
        is DomainError.NonRetryable -> Status.INTERNAL
    }
