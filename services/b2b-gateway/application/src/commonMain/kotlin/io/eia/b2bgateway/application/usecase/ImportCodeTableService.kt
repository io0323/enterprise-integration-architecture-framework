package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.CodeTableImport
import io.eia.b2bgateway.application.port.inbound.ImportCodeTableUseCase
import io.eia.b2bgateway.application.port.inbound.ImportedCodeTable
import io.eia.b2bgateway.application.port.inbound.InvalidCodeTable
import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.CodeTableSource
import io.eia.b2bgateway.application.port.outbound.CodeTableStore
import io.eia.b2bgateway.application.port.outbound.PartnerAgreements
import io.eia.b2bgateway.domain.codes.CodeTableCheck
import io.eia.b2bgateway.domain.codes.CodeTables
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.map

/** [ImportCodeTableUseCase] の実装。状態を持たない。 */
public class ImportCodeTableService(
    private val agreements: PartnerAgreements,
    private val store: CodeTableStore,
) : ImportCodeTableUseCase {
    // 台帳の読み出し・台帳にない取引先・ファイルの誤りは、それぞれ入れ替える前に返す
    @Suppress("ReturnCount")
    override suspend fun invoke(request: CodeTableImport): Result<ImportedCodeTable, DomainError> {
        agreements.find(request.partnerId).getOrElse { return err(it) } ?: return err(UnknownPartner(request.partnerId))
        val table =
            when (val check = CodeTables.check(request.kind, request.header, request.rows)) {
                is CodeTableCheck.Valid -> check.table
                is CodeTableCheck.Invalid -> return err(InvalidCodeTable(check.violations))
            }
        return store
            .replace(request.partnerId, table, CodeTableSource(request.sha256, request.operator))
            .map { version -> ImportedCodeTable(version, table.size) }
    }
}
