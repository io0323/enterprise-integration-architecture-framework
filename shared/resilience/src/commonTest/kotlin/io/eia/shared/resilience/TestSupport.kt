package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import kotlin.time.Duration

internal val UNAVAILABLE: DomainError = UnavailableError("依存先が応答しません")
internal val INVALID: DomainError = ValidationError.of("quantity", "1 以上です")

internal class Boom : RuntimeException("boom")

internal fun success(): Result<String, DomainError> = ok("ok")

internal fun failure(error: DomainError = UNAVAILABLE): Result<String, DomainError> = err(error)

internal fun <T> Result<T, DomainError>.errorOrFail(): DomainError =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

/** リスナーへの通知を順に記録する。 */
internal class RecordingListener : ResilienceListener {
    val events = mutableListOf<String>()
    val transitions = mutableListOf<Pair<CircuitState, CircuitState>>()
    val retryDelays = mutableListOf<Duration>()

    override fun onRetry(
        name: String,
        attempt: Int,
        delay: Duration,
        error: DomainError,
    ) {
        retryDelays += delay
        events += "retry:$attempt"
    }

    override fun onRetrySuppressed(
        name: String,
        reason: RetrySuppression,
    ) {
        events += "suppressed:$reason"
    }

    override fun onStateTransition(
        name: String,
        from: CircuitState,
        to: CircuitState,
    ) {
        transitions += from to to
        events += "transition:$from->$to"
    }

    override fun onRejected(rejection: ResilienceRejection) {
        events += "rejected:${rejection.code}"
    }

    override fun onTimeout(error: ResilienceError) {
        events += "timeout:${error.code}"
    }

    override fun onFallback(
        name: String,
        error: DomainError,
    ) {
        events += "fallback:${error.code}"
    }
}
