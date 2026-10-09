package io.eia.shipping.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import java.sql.SQLException

/**
 * そのトランザクションだけの一時的な失敗(直列化の失敗・デッドロック・同じ Saga の行の同時の挿入)。
 * やり直せば成功する(Consumer はその場でリトライする。ADR-0028 §2 の Transient)。
 */
public data class TransientSqlError(
    public val reason: String,
) : DomainError.Retryable {
    override val code: String get() = "transient_sql_error"
    override val message: String get() = "一時的に処理できません($reason)"
}

/**
 * SQL の例外を [DomainError] に分類する(order の SqlErrors と同じ方針。理由には SQLSTATE だけを入れ、ドライバのメッセージは入れない)。
 *
 * | SQLSTATE | 分類 | Consumer の扱い |
 * |---|---|---|
 * | 40xxx(直列化の失敗・デッドロック)・23505(同じ Saga の行の同時の挿入) | [TransientSqlError] | その場でリトライ |
 * | 08xxx(接続)・53xxx(資源不足)・57xxx(運用者の介入)・なし | [UnavailableError] | DLQ に送らず読み直す |
 * | そのほか | [UnexpectedError] | DLQ |
 */
internal object SqlErrors {
    private const val UNIQUE_VIOLATION = "23505"
    private val TRANSACTION_ROLLBACK = setOf("40")
    private val UNAVAILABLE_CLASSES = setOf("08", "53", "57")

    fun classify(e: SQLException): DomainError {
        val state = e.sqlState.orEmpty()
        val reason = "PostgreSQL: SQLSTATE ${state.ifEmpty { "なし" }}"
        return when {
            state == UNIQUE_VIOLATION || state.take(2) in TRANSACTION_ROLLBACK -> TransientSqlError(reason)
            state.isEmpty() || state.take(2) in UNAVAILABLE_CLASSES -> UnavailableError(reason)
            else -> UnexpectedError(reason)
        }
    }

    fun outsideTransaction(): DomainError = UnexpectedError("出荷の記録はトランザクションの中で書いてください")
}
