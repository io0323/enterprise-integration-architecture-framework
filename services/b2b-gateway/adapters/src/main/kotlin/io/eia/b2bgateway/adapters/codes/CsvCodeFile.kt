package io.eia.b2bgateway.adapters.codes

import io.eia.b2bgateway.adapters.archive.sha256
import io.eia.b2bgateway.application.port.inbound.CodeTableImport
import io.eia.b2bgateway.application.port.inbound.InvalidCodeTable
import io.eia.b2bgateway.domain.codes.CodeKind
import io.eia.b2bgateway.domain.codes.CodeRow
import io.eia.b2bgateway.domain.codes.CodeTableViolation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * コードの対応表の取り込むファイル(CSV。ADR-0040 §6)を読む。
 * - UTF-8(先頭の BOM は読み飛ばす)。区切りはカンマ、引用は `"`(引用の中の `""` は `"`)。行の終わりは LF か CRLF。最後の空行は無視する
 * - 1 行目は見出し。列の検査は domain の [io.eia.b2bgateway.domain.codes.CodeTables] が行う
 */
public object CsvCodeFile {
    private const val BOM = "\uFEFF"
    private const val MAX_MIB = 32
    private const val MIB = 1024 * 1024

    /** 1 ファイルの大きさの上限(対応表は大きくない。超えたら読まない)。 */
    public const val MAX_BYTES: Int = MAX_MIB * MIB

    // 大きさ・文字コード・引用・見出しの誤りは、それぞれ読むのをやめて返す
    @Suppress("ReturnCount")
    public fun read(
        partnerId: String,
        kind: CodeKind,
        bytes: ByteArray,
        operator: String,
    ): Result<CodeTableImport, DomainError> {
        if (bytes.size > MAX_BYTES) return invalid(1, "ファイルが大きすぎます($MAX_MIB MiB まで)")
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .removePrefix(BOM)
            } catch (_: CharacterCodingException) {
                return invalid(1, "UTF-8 として読めません")
            }
        val records =
            when (val parsed = parse(text)) {
                is Parsed.Records -> parsed.records
                is Parsed.Unclosed -> return invalid(parsed.row, "引用(\")が閉じていません")
            }
        if (records.isEmpty()) return invalid(1, "見出しがありません")
        return ok(CodeTableImport(partnerId, kind, records.first().values, records.drop(1), sha256(bytes), operator))
    }

    private fun invalid(
        row: Int,
        reason: String,
    ): Result<Nothing, DomainError> = err(InvalidCodeTable(listOf(CodeTableViolation(row, null, reason))))

    private sealed interface Parsed {
        class Records(
            val records: List<CodeRow>,
        ) : Parsed

        class Unclosed(
            val row: Int,
        ) : Parsed
    }

    /** RFC 4180 の CSV を、行(始まりのファイルの行の番号と値の並び)にする。引用の中の改行は値の一部。空行は飛ばす。 */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // 1 文字ずつの状態の遷移は、1 つの when にまとめたほうが読みやすい
    private fun parse(text: String): Parsed {
        val records = mutableListOf<CodeRow>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var row = 1
        var recordStart = 1
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (quoted) {
                when {
                    ch == '"' && text.getOrNull(index + 1) == '"' -> {
                        field.append('"')
                        index++
                    }

                    ch == '"' -> {
                        quoted = false
                    }

                    else -> {
                        if (ch == '\n') row++
                        field.append(ch)
                    }
                }
            } else {
                when (ch) {
                    '"' -> {
                        quoted = true
                    }

                    ',' -> {
                        record += field.toString()
                        field.clear()
                    }

                    '\r' -> {
                        Unit
                    }

                    '\n' -> {
                        record += field.toString()
                        field.clear()
                        records += CodeRow(recordStart, record)
                        record = mutableListOf()
                        row++
                        recordStart = row
                    }

                    else -> {
                        field.append(ch)
                    }
                }
            }
            index++
        }
        if (quoted) return Parsed.Unclosed(recordStart)
        if (field.isNotEmpty() || record.isNotEmpty()) records += CodeRow(recordStart, record + field.toString())
        return Parsed.Records(records.filterNot { it.values.size == 1 && it.values.single().isEmpty() })
    }
}
