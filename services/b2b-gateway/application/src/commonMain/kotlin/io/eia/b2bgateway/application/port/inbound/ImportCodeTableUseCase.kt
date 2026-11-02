package io.eia.b2bgateway.application.port.inbound

import io.eia.b2bgateway.domain.codes.CodeKind
import io.eia.b2bgateway.domain.codes.CodeRow
import io.eia.b2bgateway.domain.codes.CodeTableViolation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先の 1 つの種類のコードの対応表を、取り込むファイルの内容で丸ごと入れ替える(運用者のコマンド `codes import`。ADR-0040 §6)。
 * ファイルを検査し、誤りが 1 つでもあれば何も変えない。入れ替えと監査(`b2b.codes.import`)は 1 つのトランザクションで書き、表の版を 1 つ進める。
 *
 * @return 入れ替えた表の版と行の数。取引先が台帳にない・ファイルの誤り・DB のエラーなら、そのエラー
 */
public interface ImportCodeTableUseCase {
    public suspend operator fun invoke(request: CodeTableImport): Result<ImportedCodeTable, DomainError>
}

/**
 * 取り込むファイル。[header] と [rows] は読み込んだ CSV(値は列の順)、[sha256] はファイルの SHA-256(監査に残す)、
 * [operator] はコマンドを実行した運用者。
 */
public class CodeTableImport(
    public val partnerId: String,
    public val kind: CodeKind,
    public val header: List<String>,
    public val rows: List<CodeRow>,
    public val sha256: String,
    public val operator: String,
)

public data class ImportedCodeTable(
    val version: Int,
    val rows: Int,
)

/** 取り込むファイルの誤り(行の番号・列の名前・理由。値は入れない)。 */
public data class InvalidCodeTable(
    val violations: List<CodeTableViolation>,
) : DomainError.NonRetryable {
    override val code: String get() = "invalid_code_table"
    override val message: String
        get() =
            "取り込むファイルに誤りがあります(${violations.size} 件): " +
                violations.take(SHOWN).joinToString("; ") { "${it.row} 行目${it.column?.let { c -> " $c" }.orEmpty()}: ${it.reason}" }

    private companion object {
        // メッセージに並べる誤りの数(全件は violations にある)
        const val SHOWN = 20
    }
}
