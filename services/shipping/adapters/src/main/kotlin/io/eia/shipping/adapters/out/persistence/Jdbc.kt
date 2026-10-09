package io.eia.shipping.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.sql.Connection
import java.sql.SQLException

/** 新しいトランザクション(`Dispatchers.IO`)で [block] を実行する。打ち切られていれば確定しない(例外で取り消す)。 */
internal suspend fun <T> Database.newTransaction(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) {
        suspendTransaction(this@newTransaction) {
            block().also { currentCoroutineContext().ensureActive() }
        }
    }

/** [this] の現在のトランザクション。別の DB のトランザクションは返さない。 */
public fun Database.currentTransaction(): JdbcTransaction? = transactionManager.currentOrNull()?.takeIf { it.db == this }

internal fun JdbcTransaction.jdbcConnection(): Connection =
    connection.connection as? Connection ?: error("JDBC の接続を取得できません(${connection.connection::class.simpleName})")

/**
 * 今のトランザクションの接続で [block] を実行する。[SQLException] は [SqlErrors] で分類して `Err` にする。
 * トランザクションの外で呼ばれたら、使い方の誤り(出荷・取消・返事は 1 つのトランザクションで書く)。
 */
internal suspend fun <T> Database.inCurrentTransaction(block: (Connection) -> Result<T, DomainError>): Result<T, DomainError> {
    val transaction = currentTransaction() ?: return err(SqlErrors.outsideTransaction())
    val result =
        try {
            block(transaction.jdbcConnection())
        } catch (e: SQLException) {
            err(SqlErrors.classify(e))
        }
    // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。戻ってきた時点で打ち切られていれば、結果を使わずに伝える
    currentCoroutineContext().ensureActive()
    return result
}
