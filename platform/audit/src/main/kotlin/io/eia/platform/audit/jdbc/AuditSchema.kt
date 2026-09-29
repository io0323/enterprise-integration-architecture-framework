package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditMisuse
import io.eia.platform.audit.AuditStorageRejected
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.err
import io.eia.shared.kernel.mapError
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * 監査のテーブル(スキーマ `audit`)を作る(ADR-0017)。
 *
 * - サービスのマイグレーションとは別の履歴テーブル(`audit.audit_schema_history`)を使う。サービスの Flyway と版の番号がぶつからない。
 * - [dataSource] は DB の所有者のロールで接続する。[appRole] には INSERT と SELECT だけを付ける。
 */
public object AuditSchema {
    public const val SCHEMA: String = "audit"
    public const val TABLE: String = "$SCHEMA.audit_log"
    private const val HISTORY_TABLE = "audit_schema_history"
    private const val LOCATION = "classpath:db/audit"
    private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

    public fun migrate(
        dataSource: DataSource,
        appRole: String,
    ): Result<Unit, AuditError> {
        if (!ROLE_NAME.matches(appRole)) {
            return err(AuditMisuse("appRole は英小文字・数字・_ の 63 文字以内にしてください"))
        }
        return catching {
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(LOCATION)
                .placeholders(mapOf("appRole" to appRole))
                .load()
                .migrate()
            Unit
        }.mapError { AuditStorageRejected("PostgreSQL", it.message) }
    }
}
