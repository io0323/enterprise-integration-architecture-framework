package io.eia.shared.resilience

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 呼び出し元の締め切り(Framework 13.1 のタイムバジェット)。コルーチンのコンテキストで運び、
 * 内側の [Resilience] に引き継ぐ(ADR-0021 §12)。
 *
 * - 入口(API のハンドラなど)で [withCallDeadline] を使って置く。[Resilience] は、試行の [block][Resilience.execute] に
 *   その試行の残り時間を置くので、入れ子の [Resilience] は外側の残り時間を超えて待たない。
 * - 期限は作ったときの [TimeSource] の [TimeMark] で持つ。読む側([Resilience])が別の [TimeSource] を使っていても、
 *   [remaining] は期限を作った側の時刻で測る。
 * - 締め切りを知らせるだけで、打ち切りはしない。打ち切るのは [Resilience](や呼び出し側の `withTimeout`)。
 */
public class CallDeadline private constructor(
    private val at: TimeMark,
) : AbstractCoroutineContextElement(Key) {
    /** 期限までの残り時間。期限を過ぎていれば 0 以下。 */
    public fun remaining(): Duration = -at.elapsedNow()

    override fun toString(): String = "CallDeadline(remaining=${remaining()})"

    public companion object Key : CoroutineContext.Key<CallDeadline> {
        /** 今から [budget] 後を期限にする。 */
        public fun after(
            budget: Duration,
            timeSource: TimeSource = TimeSource.Monotonic,
        ): CallDeadline = CallDeadline(timeSource.markNow() + budget)

        /** 現在のコルーチンのコンテキストにある締め切り。なければ `null`。 */
        public suspend fun current(): CallDeadline? = currentCoroutineContext()[Key]
    }
}

/**
 * [budget] 後を締め切りにして [block] を呼ぶ。すでに締め切りがあり、その残り時間が [budget] 以下なら、それを保つ
 * (内側で締め切りを延ばせないようにする。ADR-0021 §12)。締め切りを過ぎても [block] は打ち切らない。
 */
public suspend fun <T> withCallDeadline(
    budget: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
    block: suspend CoroutineScope.() -> T,
): T {
    val deadline = CallDeadline.current()?.takeIf { it.remaining() <= budget } ?: CallDeadline.after(budget, timeSource)
    return withContext(deadline, block)
}
