package io.eia.legacysim.app

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.catching
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * legacy-sim の DB のマイグレーション(`db/legacy`)。所有者のロールで接続する。
 *
 * - V1: 既存のレガシーの受注表(改修できない前提の定義)。レガシーのアプリ([appRole])に読み書きを付ける。
 * - V2: DBA の CDC の設定(REPLICA IDENTITY FULL・Debezium([cdcRole])の権限・signal 表・publication `eiaf_legacy`)。ADR-0026
 * - V3: DBA の照合の設定(照合のロール([reconcileRole])に受注表の SELECT)。ADR-0027
 * - V4: DBA の再同期の設定(再同期のロール([resyncRole])に signal 表の INSERT)。ADR-0027 §6
 */
internal object LegacySchema {
    private const val LOCATION = "classpath:db/legacy"

    fun migrate(
        owner: DataSource,
        appRole: String,
        cdcRole: String,
        reconcileRole: String,
        resyncRole: String,
    ): Result<Unit, DomainError> =
        // Flyway の例外のメッセージは SQL 文や接続先を含みうるため、型の名前だけを入れる
        catching<Unit, DomainError>({ e -> UnexpectedError("legacy-sim のマイグレーションに失敗しました(${e::class.simpleName})", e) }) {
            Flyway
                .configure()
                .dataSource(owner)
                .locations(LOCATION)
                .placeholders(
                    mapOf("appRole" to appRole, "cdcRole" to cdcRole, "reconcileRole" to reconcileRole, "resyncRole" to resyncRole),
                ).load()
                .migrate()
            Unit
        }
}
