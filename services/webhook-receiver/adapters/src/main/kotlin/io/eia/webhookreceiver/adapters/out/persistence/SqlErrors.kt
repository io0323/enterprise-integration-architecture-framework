package io.eia.webhookreceiver.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import java.sql.SQLException

/**
 * SQL の例外を [DomainError] に分類する(order の SqlErrors と同じ方針。理由には SQLSTATE だけを入れ、ドライバのメッセージは入れない)。
 *
 * - SQLSTATE のクラスが接続(08)・トランザクションのロールバック(40)・資源不足(53)・運用者の介入(57)なら [UnavailableError]
 *   (Retryable。受け口は 503 を返し、送り手に再送させる。記録していないので重複にはならない)。
 * - そのほかは [UnexpectedError](500)。
 */
internal object SqlErrors {
    private val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")

    fun classify(e: SQLException): DomainError {
        val state = e.sqlState.orEmpty()
        val reason = "PostgreSQL: SQLSTATE ${state.ifEmpty { "なし" }}"
        return if (state.isEmpty() || state.take(2) in TRANSIENT_CLASSES) UnavailableError(reason) else UnexpectedError(reason)
    }

    fun outsideTransaction(): DomainError = UnexpectedError("受け取りの記録はトランザクションの中で書いてください")
}
