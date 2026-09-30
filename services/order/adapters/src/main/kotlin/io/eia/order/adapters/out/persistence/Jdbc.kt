package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.sql.Connection
import java.sql.SQLException

/**
 * JDBC の接続で SQL を実行する範囲。Exposed はトランザクションの管理に使い、SQL は PreparedStatement で直接書く(MODULE_DESIGN §3)。
 * [SQLException] は [SqlErrors] で分類して `Err` にする。そのほかの例外はそのまま伝える。
 */
internal interface JdbcSession {
    suspend fun <T> run(block: (Connection) -> Result<T, DomainError>): Result<T, DomainError>
}

/**
 * [database] の現在のトランザクションがあれば、その接続で実行する(呼び出し元のトランザクションに参加する)。
 * なければ、新しいトランザクション(`Dispatchers.IO`)の中で実行し、終わったら確定する。
 */
internal class ExposedJdbcSession(
    private val database: Database,
) : JdbcSession {
    override suspend fun <T> run(block: (Connection) -> Result<T, DomainError>): Result<T, DomainError> {
        val current = database.currentTransaction()
        return if (current != null) {
            // 参加した場合: 失敗した文の取り消し(セーブポイントまで戻す)は、呼び出し元の ExposedTransactionRunner が行う
            classifyingSqlErrors { block(current.jdbcConnection()) }
        } else {
            // 新しいトランザクション: 例外はトランザクションの外まで伝えて取り消させてから分類する。Err も取り消す
            classifyingSqlErrors {
                withContext(Dispatchers.IO) {
                    suspendTransaction(database) { block(jdbcConnection()).also { if (it is Result.Err) rollback() } }
                }
            }
        }
    }
}

/** [block] の [SQLException] を [SqlErrors] で分類して `Err` にする。ほかの例外はそのまま伝える。 */
internal inline fun <T> classifyingSqlErrors(block: () -> Result<T, DomainError>): Result<T, DomainError> =
    try {
        block()
    } catch (e: SQLException) {
        err(SqlErrors.classify(e))
    }

/**
 * [this] の現在のトランザクション。別の DB のトランザクションは返さない。
 * `suspendTransaction` の中では、コルーチンが再開したスレッドにも現在のトランザクションが設定される(統合テストで確かめる)。
 */
internal fun Database.currentTransaction(): JdbcTransaction? = transactionManager.currentOrNull()?.takeIf { it.db == this }

internal fun JdbcTransaction.jdbcConnection(): Connection =
    connection.connection as? Connection ?: error("JDBC の接続を取得できません(${connection.connection::class.simpleName})")
