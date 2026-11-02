package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.codes.CodeTable
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 取引先ごとのコードの対応表の置き場(引くほうは [CodeMappings])。 */
public interface CodeTableStore {
    /** 取引先の [CodeTable.kind] の表を [table] で丸ごと入れ替え、監査を記録する(1 つのトランザクション)。入れ替えた後の版を返す。 */
    public suspend fun replace(
        partnerId: String,
        table: CodeTable,
        source: CodeTableSource,
    ): Result<Int, DomainError>
}

/** 取り込んだファイルの SHA-256 と、コマンドを実行した運用者(監査の誰が)。 */
public data class CodeTableSource(
    val sha256: String,
    val operator: String,
)
