package io.eia.batchetl.adapters.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

private val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")

/** [block] を 1 つのトランザクションで実行する([transaction] の、失敗を返さない処理の版)。 */
internal suspend fun <T> DataSource.inTransaction(block: (Connection) -> T): Result<T, DomainError> = transaction { ok(block(it)) }

/**
 * [block] を 1 つのトランザクションで実行し、Ok なら確定、Err なら取り消す。SQL の例外は SQLSTATE で分類する
 * (接続・ロールバック・資源不足・運用者の介入は一時的)。理由には SQLSTATE だけを入れる(制約違反の DETAIL に行の値が含まれるため)。
 */
internal suspend fun <T> DataSource.transaction(block: (Connection) -> Result<T, DomainError>): Result<T, DomainError> =
    withContext(Dispatchers.IO) {
        try {
            connection.use { connection ->
                connection.autoCommit = false
                try {
                    block(connection).also { if (it is Result.Ok) connection.commit() else connection.rollback() }
                } catch (e: SQLException) {
                    connection.rollback()
                    throw e
                }
            }
        } catch (e: SQLException) {
            err(classify(e))
        }
    }

/** SQL の例外を SQLSTATE で分類する。理由には SQLSTATE だけを入れる。 */
internal fun classify(e: SQLException): DomainError {
    val state = e.sqlState.orEmpty()
    return if (state.isEmpty() || state.take(2) in TRANSIENT_CLASSES) {
        UnavailableError("分析の DB を使えません(SQLSTATE ${state.ifEmpty { "なし" }})")
    } else {
        UnexpectedError("分析の DB に拒否されました(SQLSTATE $state)")
    }
}
