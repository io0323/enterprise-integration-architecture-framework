package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.SQLException
import java.sql.Savepoint

/**
 * [TransactionRunner] の Exposed の実装(③b の約束)。
 *
 * - **トランザクションの外で呼ばれたら**、新しいトランザクション(`Dispatchers.IO`)を開く。`Ok` なら確定し、`Err` なら取り消して
 *   `Err` を返す。例外なら取り消して伝える。
 * - **呼び出し元のトランザクションの中で呼ばれたら、それに参加する**(P05 ④b で、冪等の応答の保存と注文の保存を同じトランザクションに
 *   するため。ADR-0022 §3)。`Err` と例外のときは、呼び出した時点のセーブポイントまでだけ取り消す(外側の書き込みは残す)。
 *   `Ok` の書き込みは、外側のトランザクションが確定したときにだけ残る。
 */
public class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        val current = database.currentTransaction()
        return if (current != null) joined(current, block) else newTransaction(block)
    }

    private suspend fun <T> newTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> =
        database.newTransaction { block().also { if (it is Result.Err) rollback() } }

    @Suppress("TooGenericExceptionCaught") // 例外はセーブポイントまで戻してから、そのまま伝える
    private suspend fun <T> joined(
        transaction: JdbcTransaction,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        val connection = transaction.jdbcConnection()
        val savepoint: Savepoint = connection.setSavepoint()
        try {
            val result = block()
            // 打ち切られていれば(コルーチンの打ち切り)、結果を使わずに例外でセーブポイントまで戻す
            currentCoroutineContext().ensureActive()
            if (result is Result.Err) connection.rollback(savepoint) else connection.releaseSavepoint(savepoint)
            return result
        } catch (e: Throwable) {
            try {
                connection.rollback(savepoint)
            } catch (rollbackFailure: SQLException) {
                // 元の例外を隠さない
                e.addSuppressed(rollbackFailure)
            }
            throw e
        }
    }
}
