package io.eia.b2bgateway.adapters.persistence

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

/** 一意制約の違反(同じ交換を 2 つの処理が同時に記録した。やり直せば、送り直しとして扱われる)。 */
internal const val UNIQUE_VIOLATION = "23505"

/**
 * [block] を 1 つのトランザクションで実行して確定する。SQL の例外は SQLSTATE で分類する(接続・ロールバック・資源不足・運用者の介入と、
 * [retryable] の SQLSTATE は一時的)。理由には SQLSTATE だけを入れる(制約違反の DETAIL に行の値が含まれるため)。
 */
internal suspend fun <T> DataSource.inTransaction(
    retryable: Set<String> = emptySet(),
    block: (Connection) -> T,
): Result<T, DomainError> =
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
            if (state.isEmpty() || state.take(2) in TRANSIENT_CLASSES || state in retryable) {
                err(UnavailableError("受け取りの記録の DB を使えません(SQLSTATE ${state.ifEmpty { "なし" }})"))
            } else {
                err(UnexpectedError("受け取りの記録の DB に拒否されました(SQLSTATE $state)"))
            }
        }
    }

/** 監査を追記できなかった(トランザクションを巻き戻す。理由は監査のエラーのコードだけ。一時的な失敗は、やり直せる SQLSTATE にする)。 */
internal class AuditFailed(
    code: String,
    retryable: Boolean,
) : SQLException("監査の記録に失敗しました($code)", if (retryable) "40001" else "XX000")
