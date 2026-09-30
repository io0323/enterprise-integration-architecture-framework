package io.eia.order.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 業務の更新の範囲(トランザクション)。[block] が `Ok` を返せば確定し、`Err` を返すか例外を投げれば取り消す。
 *
 * 呼び出し元がすでにトランザクションの中にいれば、それに参加する(P05 ④b で、冪等の応答の保存と注文の保存を
 * 同じトランザクションにするため。ADR-0022 §3)。
 */
public interface TransactionRunner {
    public suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError>
}
