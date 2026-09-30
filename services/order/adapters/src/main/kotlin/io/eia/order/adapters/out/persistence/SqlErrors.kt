package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import java.sql.SQLException

/**
 * SQL の例外を [DomainError] に分類する(platform/audit の SqlErrors と同じ方針)。
 *
 * - SQLSTATE のクラスが接続(08)・トランザクションのロールバック(40。直列化の失敗・デッドロック)・資源不足(53)・
 *   運用者の介入(57)なら [UnavailableError](Retryable。API では 503)。
 * - そのほかは [UnexpectedError](API では 500)。
 * - 理由には SQLSTATE だけを入れ、ドライバのメッセージは入れない(制約違反の DETAIL に行の値がそのまま含まれるため)。
 */
internal object SqlErrors {
    const val UNIQUE_VIOLATION: String = "23505"
    private val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")

    fun classify(e: SQLException): DomainError {
        val state = e.sqlState.orEmpty()
        val reason = "PostgreSQL: SQLSTATE ${state.ifEmpty { "なし" }}"
        return if (state.isEmpty() || state.take(2) in TRANSIENT_CLASSES) UnavailableError(reason) else UnexpectedError(reason)
    }
}
