package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/** Circuit Breaker の状態。 */
public enum class CircuitState {
    /** 通常。失敗率を集計する。 */
    CLOSED,

    /** 遮断中。呼び出さずに [CircuitOpen] を返す。 */
    OPEN,

    /** 回復を試す。[CircuitBreakerConfig.halfOpenPermits] 件だけ通す。 */
    HALF_OPEN,
}

/**
 * Circuit Breaker(Framework 13.1。ADR-0021 §4)。依存先ごとに 1 つのインスタンスを共有する。
 *
 * - **数え方**: Retryable なエラー(タイムアウトを含む)を失敗、Ok と NonRetryable を成功に数える。
 *   手元で断った呼び出し([ResilienceRejection])と、例外・キャンセルで終わった呼び出しは数えない。
 * - **遷移**: Closed で窓の件数が [CircuitBreakerConfig.minimumCalls] 以上かつ失敗率がしきい値以上なら Open。
 *   Open は [CircuitBreakerConfig.openDuration] が過ぎた後の最初の呼び出しで Half-Open になる(時間だけでは遷移しない)。
 *   Half-Open は試した全件が成功すれば Closed、1 件でも失敗すれば Open に戻る。
 * - **並行性**: 状態は [Mutex] で守る。遷移のたびに世代を進め、前の世代で始まった呼び出しの結果は数えない
 *   (Open の後に遅れて返ってきた Closed の呼び出しで、Half-Open の判定が狂わないように)。
 * - **時間**: Open の期間は [timeSource] の単調な時刻で測る。壁時計(`Clock`)は巻き戻りうるため使わない(ADR-0021 §6)。
 */
public class CircuitBreaker(
    public val name: String,
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val listener: ResilienceListener = ResilienceListener.NONE,
) {
    private val mutex = Mutex()

    /** 現在の状態。Open の期間が過ぎても、次の呼び出しまでは [CircuitState.OPEN] のまま。 */
    @Volatile
    public var state: CircuitState = CircuitState.CLOSED
        private set

    private var generation = 0L
    private var window = FailureWindow.of(config.window, timeSource)
    private var openedAt: ComparableTimeMark = timeSource.markNow()
    private var halfOpenIssued = 0
    private var halfOpenSucceeded = 0

    /** [block] を呼ぶ。遮断中は呼ばずに [CircuitOpen] を返す。[block] の例外はそのまま伝える。 */
    public suspend fun <T> execute(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        val permit =
            when (val acquired = acquire()) {
                is Acquisition.Rejected -> {
                    listener.onRejected(acquired.error)
                    return err(acquired.error)
                }

                is Acquisition.Permit -> {
                    acquired
                }
            }
        var outcome = Outcome.IGNORED
        try {
            return block().also { outcome = Outcome.of(it) }
        } finally {
            // キャンセルされても枠を返せるように、キャンセルできない文脈で記録する
            withContext(NonCancellable) { complete(permit, outcome) }
        }
    }

    private suspend fun acquire(): Acquisition {
        var transition: Transition? = null
        val acquisition =
            mutex.withLock {
                when (state) {
                    CircuitState.CLOSED -> {
                        Acquisition.Permit(generation)
                    }

                    CircuitState.OPEN -> {
                        val remaining = config.openDuration - openedAt.elapsedNow()
                        if (remaining.isPositive()) {
                            Acquisition.Rejected(CircuitOpen(name, remaining))
                        } else {
                            transition = moveTo(CircuitState.HALF_OPEN)
                            issueHalfOpen()
                        }
                    }

                    CircuitState.HALF_OPEN -> {
                        issueHalfOpen()
                    }
                }
            }
        transition?.publish()
        return acquisition
    }

    private fun issueHalfOpen(): Acquisition =
        if (halfOpenIssued < config.halfOpenPermits) {
            halfOpenIssued++
            Acquisition.Permit(generation)
        } else {
            Acquisition.Rejected(CircuitOpen(name, retryAfter = null))
        }

    private suspend fun complete(
        permit: Acquisition.Permit,
        outcome: Outcome,
    ) {
        val transition =
            mutex.withLock {
                if (permit.generation != generation) return
                when (state) {
                    CircuitState.CLOSED -> recordClosed(outcome)
                    CircuitState.HALF_OPEN -> recordHalfOpen(outcome)
                    CircuitState.OPEN -> null
                }
            }
        transition?.publish()
    }

    private fun recordClosed(outcome: Outcome): Transition? {
        if (outcome == Outcome.IGNORED) return null
        window.record(failed = outcome == Outcome.FAILURE)
        val counts = window.snapshot()
        val tripped =
            counts.total >= config.minimumCalls &&
                counts.failures.toDouble() / counts.total >= config.failureRateThreshold
        return if (tripped) moveTo(CircuitState.OPEN) else null
    }

    private fun recordHalfOpen(outcome: Outcome): Transition? =
        when (outcome) {
            // 数えない結果は、試す枠を返して別の呼び出しに試させる
            Outcome.IGNORED -> {
                halfOpenIssued--
                null
            }

            Outcome.FAILURE -> {
                moveTo(CircuitState.OPEN)
            }

            Outcome.SUCCESS -> {
                halfOpenSucceeded++
                if (halfOpenSucceeded >= config.halfOpenPermits) moveTo(CircuitState.CLOSED) else null
            }
        }

    /** ロックの中で状態を変える。リスナーへの通知は、ロックを外した後に [Transition.publish] で行う。 */
    private fun moveTo(to: CircuitState): Transition {
        val from = state
        state = to
        generation++
        when (to) {
            CircuitState.CLOSED -> {
                window = FailureWindow.of(config.window, timeSource)
            }

            CircuitState.OPEN -> {
                openedAt = timeSource.markNow()
            }

            CircuitState.HALF_OPEN -> {
                halfOpenIssued = 0
                halfOpenSucceeded = 0
            }
        }
        return Transition(from, to)
    }

    private inner class Transition(
        private val from: CircuitState,
        private val to: CircuitState,
    ) {
        fun publish() = listener.onStateTransition(name, from, to)
    }

    private sealed interface Acquisition {
        data class Permit(
            val generation: Long,
        ) : Acquisition

        data class Rejected(
            val error: CircuitOpen,
        ) : Acquisition
    }
}
