package io.eia.inventory.adapters.out.persistence

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

internal val STATEMENT_TIMEOUT_MARGIN = 500.milliseconds

/**
 * 新しいトランザクション(`Dispatchers.IO`)で [block] を実行する。打ち切られていれば確定しない(例外で取り消す)。
 *
 * 呼び出し元の締め切り(`CallDeadline`。gRPC の照会で置く。ADR-0042 §6)があれば、`SET LOCAL statement_timeout` に「残り時間 + 余裕」を設定する。
 * コルーチンの打ち切りでは、待ち状態の JDBC の問い合わせは止まらないため(ADR-0024 §3)。コマンドの処理には締め切りがないので、設定しない。
 */
internal suspend fun <T> Database.newTransaction(block: suspend JdbcTransaction.() -> T): T {
    val remaining = CallDeadline.current()?.remaining()
    return withContext(Dispatchers.IO) {
        suspendTransaction(this@newTransaction) {
            remaining?.let { applyStatementTimeout(jdbcConnection(), it) }
            block().also { currentCoroutineContext().ensureActive() }
        }
    }
}

/** このトランザクションの間だけ、文の実行時間の上限を「[remaining] + 余裕」にする(1 ミリ秒以上)。 */
internal fun applyStatementTimeout(
    connection: Connection,
    remaining: Duration,
) {
    val millis = (remaining + STATEMENT_TIMEOUT_MARGIN).inWholeMilliseconds.coerceAtLeast(1)
    connection.createStatement().use { it.execute("SET LOCAL statement_timeout = $millis") }
}

/** [this] の現在のトランザクション。別の DB のトランザクションは返さない。 */
public fun Database.currentTransaction(): JdbcTransaction? = transactionManager.currentOrNull()?.takeIf { it.db == this }

internal fun JdbcTransaction.jdbcConnection(): Connection =
    connection.connection as? Connection ?: error("JDBC の接続を取得できません(${connection.connection::class.simpleName})")

/**
 * 今のトランザクションの接続で [block] を実行する。[SQLException] は [SqlErrors] で分類して `Err` にする。
 * トランザクションの外で呼ばれたら、使い方の誤り(引当・在庫・返事は 1 つのトランザクションで書く)。
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
