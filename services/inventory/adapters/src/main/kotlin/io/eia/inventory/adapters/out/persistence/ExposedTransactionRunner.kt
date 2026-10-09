package io.eia.inventory.adapters.out.persistence

import io.eia.inventory.application.port.outbound.TransactionRunner
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [TransactionRunner] の Exposed の実装。新しいトランザクション(`Dispatchers.IO`)を開き、`Ok` なら確定し、`Err` なら取り消して `Err` を返す。
 * 例外なら取り消して伝える。コマンドの処理は入れ子にしない(1 件 = 1 トランザクション)。
 */
public class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        check(database.currentTransaction() == null) { "コマンドの処理のトランザクションは入れ子にしません" }
        return database.newTransaction { block().also { if (it is Result.Err) rollback() } }
    }
}
