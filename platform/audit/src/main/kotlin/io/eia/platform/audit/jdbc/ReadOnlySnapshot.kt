package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditMisuse
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import java.sql.Connection

/**
 * [block] を 1 つの REPEATABLE READ の読み取り専用トランザクション(同じスナップショット)で実行し、最後に巻き戻して接続の設定を戻す。
 * 検査の間にサービスが追記しても、読んだ範囲が食い違わないようにする(ADR-0017 §6)。
 *
 * [connection] は検査専用の接続で、自動コミットが有効であること(呼び出し側のトランザクションを巻き戻さないため)。
 */
internal fun <T> inReadOnlySnapshot(
    connection: Connection,
    block: () -> Result<T, AuditError>,
): Result<T, AuditError> =
    sqlCatching {
        if (!connection.autoCommit) {
            return@sqlCatching err(AuditMisuse("検証は、自動コミットが有効な検証専用の接続で行ってください"))
        }
        val isolation = connection.transactionIsolation
        val readOnly = connection.isReadOnly
        connection.autoCommit = false
        try {
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            block()
        } finally {
            connection.rollback()
            connection.transactionIsolation = isolation
            connection.isReadOnly = readOnly
            connection.autoCommit = true
        }
    }
