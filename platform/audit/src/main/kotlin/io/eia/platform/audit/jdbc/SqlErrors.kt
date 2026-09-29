package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditStorageRejected
import io.eia.platform.audit.AuditStorageUnavailable
import java.sql.SQLException

internal object SqlErrors {
    private const val STORAGE = "PostgreSQL"

    /**
     * SQLSTATE のクラスで分類する。接続(08)・トランザクションのロールバック(40)・資源不足(53)・運用者の介入(57)は一時的な失敗とする。
     *
     * 理由には SQLSTATE だけを入れ、ドライバのメッセージは入れない(制約違反の DETAIL に行の値がそのまま含まれるため)。
     */
    fun classify(e: SQLException): AuditError {
        val state = e.sqlState.orEmpty()
        val reason = "SQLSTATE ${state.ifEmpty { "なし" }}"
        return if (state.take(2) in TRANSIENT_CLASSES || state.isEmpty()) {
            AuditStorageUnavailable(STORAGE, reason)
        } else {
            AuditStorageRejected(STORAGE, reason)
        }
    }

    private val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")
}
