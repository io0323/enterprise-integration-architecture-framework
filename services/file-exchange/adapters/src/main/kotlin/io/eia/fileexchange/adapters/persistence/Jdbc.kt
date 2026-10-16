package io.eia.fileexchange.adapters.persistence

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

/**
 * [block] を 1 つのトランザクションで実行して確定する。SQL の例外は SQLSTATE で分類する(接続・ロールバック・資源不足・運用者の介入は
 * 一時的)。理由には SQLSTATE だけを入れる(制約違反の DETAIL に行の値が含まれるため)。
 */
internal suspend fun <T> DataSource.inTransaction(block: (Connection) -> T): Result<T, DomainError> =
    withContext(Dispatchers.IO) {
        try {
            connection.use { connection ->
                connection.autoCommit = false
                try {
                    ok(block(connection)).also { connection.commit() }
                } catch (e: SQLException) {
                    connection.rollback()
                    throw e
                }
            }
        } catch (e: SQLException) {
            val state = e.sqlState.orEmpty()
            if (state.isEmpty() || state.take(2) in TRANSIENT_CLASSES) {
                err(UnavailableError("受け取りの記録の DB を使えません(SQLSTATE ${state.ifEmpty { "なし" }})"))
            } else {
                err(UnexpectedError("受け取りの記録の DB に拒否されました(SQLSTATE $state)"))
            }
        }
    }
