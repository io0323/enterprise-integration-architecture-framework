package io.eia.order.adapters.out.persistence

import io.eia.platform.api.idempotency.TransactionBoundary
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * `platform/api` の冪等の処理(`IdempotencyHandler`)が使う、業務の更新の範囲(ADR-0022 §3)。
 *
 * 新しいトランザクションを開き、[run] の処理が例外で終われば取り消す(保存しない応答は、`IdempotencyHandler` が例外で取り消させる)。
 * 中で呼ぶ [ExposedTransactionRunner]・[ExposedOrderRepository]・[PostgresIdempotencyStore.complete] は、このトランザクションに参加する。
 * そのため、業務の更新と応答の保存は一緒に確定するか、一緒に取り消される。
 * リクエストの締め切りがあれば DB 側にも打ち切りをかけ、打ち切られた後は確定しない([newTransaction]。ADR-0024 §3)。
 */
public class ExposedTransactionBoundary(
    private val database: Database,
) : TransactionBoundary {
    override suspend fun <T> run(block: suspend () -> T): T {
        check(database.currentTransaction() == null) { "冪等の処理の範囲は、いちばん外側のトランザクションにしてください" }
        return database.newTransaction { block() }
    }
}
