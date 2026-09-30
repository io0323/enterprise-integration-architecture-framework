package io.eia.order.adapters.out.persistence

import io.eia.platform.api.idempotency.TransactionBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * `platform/api` の冪等の処理(`IdempotencyHandler`)が使う、業務の更新の範囲(ADR-0022 §3)。
 *
 * 新しいトランザクションを開き、[run] の処理が例外で終われば取り消す(保存しない応答は、`IdempotencyHandler` が例外で取り消させる)。
 * 中で呼ぶ [ExposedTransactionRunner]・[ExposedOrderRepository]・[PostgresIdempotencyStore.complete] は、このトランザクションに参加する。
 * そのため、業務の更新と応答の保存は一緒に確定するか、一緒に取り消される。
 */
public class ExposedTransactionBoundary(
    private val database: Database,
) : TransactionBoundary {
    override suspend fun <T> run(block: suspend () -> T): T {
        check(database.currentTransaction() == null) { "冪等の処理の範囲は、いちばん外側のトランザクションにしてください" }
        return withContext(Dispatchers.IO) { suspendTransaction(database) { block() } }
    }
}
