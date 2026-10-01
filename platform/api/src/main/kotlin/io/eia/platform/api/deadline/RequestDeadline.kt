package io.eia.platform.api.deadline

import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.observability.ktor.server.markTimedOut
import io.eia.shared.resilience.withCallDeadline
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 予算切れの応答の `Retry-After` の既定(ADR-0024 §3)。 */
public val DEFAULT_DEADLINE_RETRY_AFTER: Duration = 1.seconds

/**
 * リクエストごとの予算(Framework 13.1 のタイムバジェット。ADR-0024 §3)。
 *
 * - [budget] を `CallDeadline` としてコンテキストに置く(`withCallDeadline`)。入れ子の `Resilience`(ADR-0021 §12)と、DB の打ち切り
 *   (adapters の `SET LOCAL statement_timeout`)が、残り時間を引き継ぐ。
 * - あわせて `withTimeoutOrNull(budget)` で処理全体を打ち切る。`withCallDeadline` は締め切りを伝えるだけで打ち切らないため
 *   (#8 のチェックリスト)。
 * - 打ち切ったら 503 `deadline-exceeded`(Problem Details。`Retry-After` は [retryAfter])を返す。処理は確定していないので、
 *   クライアントは同じ `Idempotency-Key` で再試行できる。ゲートウェイの上流のタイムアウト(504。結果は分からない)と区別する
 *   ために 504 にはしない(ADR-0024 §3)。`ServerObservability` には [markTimedOut] で伝え、`error.type=timeout` で数える。
 * - 打ち切るのはこの予算の期限切れだけ。処理の中の別の `withTimeout` の期限切れは、これまでどおり例外として伝わる。
 * - `withTimeoutOrNull` はコルーチンを打ち切るだけで、待ち状態の JDBC の問い合わせは止まらない。DB 側の打ち切りと、打ち切られた後に
 *   確定しないことは、永続化の adapters が保証する(ADR-0024 §3)。
 *
 * `ServerObservability`(Monitoring の段階)より内側の、`Plugins` の段階で動く。
 */
public fun Application.installRequestDeadline(
    budget: Duration,
    retryAfter: Duration = DEFAULT_DEADLINE_RETRY_AFTER,
) {
    require(budget.isPositive()) { "budget は正の値です: $budget" }
    require(!retryAfter.isNegative()) { "retryAfter は 0 以上です: $retryAfter" }
    intercept(ApplicationCallPipeline.Plugins) {
        withCallDeadline(budget) {
            val completed = withTimeoutOrNull(budget) { proceed() } != null
            if (!completed) {
                call.markTimedOut()
                // 応答を書き始めた後なら、状態コードは変えられない(記録だけする)
                if (!call.response.isCommitted) {
                    call.respondProblem(Problem(ProblemType.DEADLINE_EXCEEDED, retryAfter = retryAfter))
                }
            }
        }
    }
}
