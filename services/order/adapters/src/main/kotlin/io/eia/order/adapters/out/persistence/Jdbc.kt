package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.resilience.CallDeadline
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
        val result =
            if (current != null) {
                // 参加した場合: 失敗した文の取り消し(セーブポイントまで戻す)は、呼び出し元の ExposedTransactionRunner が行う
                classifyingSqlErrors { block(current.jdbcConnection()) }
            } else {
                // 新しいトランザクション: 例外はトランザクションの外まで伝えて取り消させてから分類する。Err も取り消す
                classifyingSqlErrors {
                    database.newTransaction { block(jdbcConnection()).also { if (it is Result.Err) rollback() } }
                }
            }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。戻ってきた時点で打ち切られていれば、結果を使わずに伝える
        currentCoroutineContext().ensureActive()
        return result
    }
}

/** DB の打ち切りの余裕。コルーチンの打ち切り(withTimeout)が先に来て、DB の打ち切りがそれを追う順にする(ADR-0024 §3)。 */
internal val STATEMENT_TIMEOUT_MARGIN = 500.milliseconds

/**
 * 新しいトランザクション(`Dispatchers.IO`)で [block] を実行する(ADR-0024 §3)。
 *
 * - **DB 側の打ち切り**: 呼び出し元の締め切り(`CallDeadline`)があれば、`SET LOCAL statement_timeout` に「残り時間 + 余裕」を設定する。
 *   `withTimeout` はコルーチンを打ち切るだけで、待ち状態の JDBC の問い合わせは止まらないため。
 * - **打ち切られた後に確定しない**: [block] の後、確定の前に `ensureActive()` で確かめる。打ち切られていれば例外でトランザクションを
 *   取り消す。
 * - [applyDeadline] が false なら、締め切りを DB に設定しない(打ち切られた後の後始末。例: 冪等の処理中の記録の取り消し)。
 */
internal suspend fun <T> Database.newTransaction(
    applyDeadline: Boolean = true,
    block: suspend JdbcTransaction.() -> T,
): T {
    val remaining = if (applyDeadline) CallDeadline.current()?.remaining() else null
    return withContext(Dispatchers.IO) {
        suspendTransaction(this@newTransaction) {
            remaining?.let { applyStatementTimeout(jdbcConnection(), it) }
            block().also { currentCoroutineContext().ensureActive() }
        }
    }
}

/** この トランザクションの間だけ、文の実行時間の上限を「[remaining] + 余裕」にする(1 ミリ秒以上)。 */
internal fun applyStatementTimeout(
    connection: Connection,
    remaining: Duration,
) {
    val millis = (remaining + STATEMENT_TIMEOUT_MARGIN).inWholeMilliseconds.coerceAtLeast(1)
    connection.createStatement().use { it.execute("SET LOCAL statement_timeout = $millis") }
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
