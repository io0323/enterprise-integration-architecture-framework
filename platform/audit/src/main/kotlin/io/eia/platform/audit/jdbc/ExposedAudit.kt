package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditMisuse
import io.eia.platform.audit.AuditRecord
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Connection

/**
 * Exposed のトランザクションの中で監査記録を追記する。業務の更新と同じトランザクションに入る(ADR-0017)。
 *
 * ```
 * transaction(database) {
 *     orders.insert { ... }
 *     auditLog.appendAudit(this, event)
 * }
 * ```
 */
public fun AuditLog.appendAudit(
    transaction: JdbcTransaction,
    event: AuditEvent,
): Result<AuditRecord, AuditError> {
    val connection = transaction.connection.connection as? Connection ?: return err(AuditMisuse("JDBC の接続を取得できません"))
    return append(connection, event)
}
