package io.eia.platform.filetransfer.write

import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.CsvSettings
import io.eia.platform.filetransfer.layout.FileColumn
import io.eia.platform.filetransfer.layout.FileLayout
import java.io.BufferedWriter
import java.io.InputStream
import java.io.Reader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * CSV(RFC 4180。契約の formats[type=csv])の書き出しと、件数の数え方。
 * - 区切り・囲み・改行を含む値と、空の文字列(null と区別するため)を囲む。囲みの文字は 2 つ重ねる
 * - 件数はレコードの数(ヘッダを除く)。囲みの中の改行はレコードの区切りにしない
 */
internal object CsvFiles {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun writeHeader(
        out: BufferedWriter,
        layout: FileLayout,
    ) {
        if (layout.csv.header) writeRecord(out, layout.csv, layout.columns.map { it.name })
    }

    fun writeRow(
        out: BufferedWriter,
        layout: FileLayout,
        row: List<Any?>,
    ) {
        writeRecord(out, layout.csv, layout.columns.zip(row).map { (column, value) -> field(layout.csv, column, value) })
    }

    private fun writeRecord(
        out: BufferedWriter,
        csv: CsvSettings,
        fields: List<String>,
    ) {
        fields.forEachIndexed { index, field ->
            if (index > 0) out.write(csv.delimiter.code)
            out.write(field)
        }
        out.write(csv.lineSeparator)
    }

    /** 1 つの値の書き方(囲みを含む)。null は nullValue をそのまま書く。 */
    private fun field(
        csv: CsvSettings,
        column: FileColumn,
        value: Any?,
    ): String {
        if (value == null) return csv.nullValue
        val text =
            when (column.type) {
                ColumnType.TIMESTAMP -> TIMESTAMP.format(value as Instant)

                // LocalDate の toString は ISO 8601(YYYY-MM-DD)
                else -> value.toString()
            }
        val needsQuote =
            text.isEmpty() ||
                text == csv.nullValue ||
                text.any { it == csv.delimiter || it == csv.quote || it == '\r' || it == '\n' }
        return if (needsQuote) {
            val doubled = text.replace(csv.quote.toString(), "${csv.quote}${csv.quote}")
            "${csv.quote}$doubled${csv.quote}"
        } else {
            text
        }
    }

    /**
     * レコードを数え、ヘッダを返す([CsvCount])。囲みが閉じていない・最後のレコードが改行で終わらないファイルは [CsvCount.malformed]。
     * 1 つのフィールドの長さには上限を設けない(ファイル全体の大きさは置き場の側で決まる)。ヘッダだけは上限まで覚える。
     */
    fun count(
        input: InputStream,
        csv: CsvSettings,
    ): CsvCount = Counter(input.reader(Charsets.UTF_8).buffered(), csv).run()

    private class Counter(
        private val reader: Reader,
        private val csv: CsvSettings,
    ) {
        private var records = 0L
        private var quoted = false
        private var atRecordStart = true
        private var endedWithNewline = true
        private val header = StringBuilder()
        private var inHeader = csv.header

        fun run(): CsvCount {
            while (true) {
                val c = reader.read()
                if (c < 0) break
                accept(c.toChar())
            }
            val malformed = quoted || (!atRecordStart && !endedWithNewline)
            val headerFields = if (csv.header) parseHeader() else null
            return CsvCount(records - if (csv.header && records > 0) 1 else 0, headerFields, malformed)
        }

        private fun accept(ch: Char) {
            if (inHeader && header.length < MAX_HEADER) header.append(ch)
            atRecordStart = false
            endedWithNewline = false
            when {
                ch == csv.quote -> quoted = !quoted
                !quoted && ch == '\n' -> endRecord()
                else -> Unit
            }
        }

        /** 囲みの外の改行(LF。CRLF の CR はその前の文字)でレコードを終える。 */
        private fun endRecord() {
            records++
            atRecordStart = true
            endedWithNewline = true
            inHeader = false
        }

        private fun parseHeader(): List<String> =
            header
                .toString()
                .removeSuffix("\n")
                .removeSuffix("\r")
                .split(csv.delimiter)
                .map { it.removeSurrounding(csv.quote.toString()) }
    }

    private const val MAX_HEADER = 64 * 1024
}

/**
 * @property records データのレコードの数(ヘッダを除く)
 * @property header ヘッダの列の名前(ヘッダのない形式は null)
 * @property malformed 囲みが閉じていない・最後のレコードが改行で終わらない
 */
internal data class CsvCount(
    val records: Long,
    val header: List<String>?,
    val malformed: Boolean,
)
