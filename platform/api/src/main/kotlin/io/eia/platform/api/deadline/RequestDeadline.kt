package io.eia.platform.api.deadline

import io.eia.shared.resilience.withCallDeadline
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * リクエストごとの予算(Framework 13.1 のタイムバジェット。ADR-0024 §3)。
 *
 * - [budget] を `CallDeadline` としてコンテキストに置く(`withCallDeadline`)。入れ子の `Resilience`(ADR-0021 §12)と、DB の打ち切り
 *   (adapters の `SET LOCAL statement_timeout`)が、残り時間を引き継ぐ。
 * - あわせて `withTimeout(budget)` で処理全体を打ち切る。`withCallDeadline` は締め切りを伝えるだけで打ち切らないため
 *   (#8 のチェックリスト)。打ち切りは `TimeoutCancellationException` で、Ktor が 504 を返し、`ServerObservability` が
 *   `error.type=timeout` で記録する(ADR-0018 §5)。
 * - `withTimeout` はコルーチンを打ち切るだけで、待ち状態の JDBC の問い合わせは止まらない。DB 側の打ち切りと、打ち切られた後に
 *   確定しないことは、永続化の adapters が保証する(ADR-0024 §3)。
 *
 * `ServerObservability`(Monitoring の段階)より内側の、`Plugins` の段階で動く。
 */
public fun Application.installRequestDeadline(budget: Duration) {
    require(budget.isPositive()) { "budget は正の値です: $budget" }
    intercept(ApplicationCallPipeline.Plugins) {
        withCallDeadline(budget) {
            withTimeout(budget) { proceed() }
        }
    }
}
