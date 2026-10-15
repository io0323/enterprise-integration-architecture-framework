package io.eia.platform.filetransfer.layout

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * データファイルの形(`contracts/files/{system}_{dataset}.v{n}.yaml`。INTEGRATION_STANDARDS「ファイルの仕様」)をコードにしたもの。
 * 送る側(書き出し)と受ける側(検査)が同じものを使う。契約と一致させるのは利用者の責任(契約のファイルを実行時には読まない)。
 *
 * @property schemaVersion manifest の schemaVersion(`{system}_{dataset}.v{version}`)
 */
public class FileLayout private constructor(
    public val system: String,
    public val dataset: String,
    public val version: Int,
    public val columns: List<FileColumn>,
    public val csv: CsvSettings,
) {
    public val schemaVersion: String get() = "${system}_$dataset.v$version"

    override fun toString(): String = "FileLayout($schemaVersion)"

    public companion object {
        private val NAME_PART = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val COLUMN_NAME = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

        /** 形の誤り(名前の形式・列の重複・列がない)は [FileTransferMisuse]。 */
        public fun of(
            system: String,
            dataset: String,
            version: Int,
            columns: List<FileColumn>,
            csv: CsvSettings = CsvSettings.RFC4180,
        ): Result<FileLayout, FileTransferError> {
            val problems =
                buildList {
                    if (!NAME_PART.matches(system)) add("system の形式")
                    if (!NAME_PART.matches(dataset)) add("dataset の形式")
                    if (version < 1) add("version は 1 以上")
                    if (columns.isEmpty()) add("列がありません")
                    columns.filterNot { COLUMN_NAME.matches(it.name) }.forEach { add("列の名前の形式(${it.name})") }
                    columns
                        .groupingBy { it.name }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                        .forEach { add("列の重複($it)") }
                }
            return if (problems.isEmpty()) {
                ok(FileLayout(system, dataset, version, columns, csv))
            } else {
                err(FileTransferMisuse("ファイルの形が誤っています: ${problems.joinToString()}"))
            }
        }
    }
}

/**
 * 列。値の型は [ColumnType] ごとに決まる。
 *
 * @property enumValues 取りうる値(string の列だけ)。書き出しで値を確かめる。null なら確かめない
 */
public data class FileColumn(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val enumValues: Set<String>? = null,
)

/**
 * 列の論理型(契約の columns[].type)と、行で渡す Kotlin の値の型。
 * decimal は今の利用者がいないので扱わない(使うときに足す)。
 */
public enum class ColumnType(
    /** 行の値として受け付ける型の名前(誤りの説明に使う)。 */
    public val valueType: String,
) {
    /** UTF-8 の文字列。値は [String] */
    STRING("String"),

    /** 値は [Int] */
    INT32("Int"),

    /** 値は [Long] */
    INT64("Long"),

    /** 値は [Boolean] */
    BOOLEAN("Boolean"),

    /** 日付。値は [java.time.LocalDate]。Parquet は DATE(INT32)、CSV は ISO 8601(YYYY-MM-DD) */
    DATE("LocalDate"),

    /** UTC の時刻(マイクロ秒)。値は [java.time.Instant]。Parquet は TIMESTAMP(MICROS, UTC)、CSV は ISO 8601 の Z 付き(小数 6 桁) */
    TIMESTAMP("Instant"),
}

/**
 * CSV の書き方(契約の formats[type=csv])。既定は RFC 4180(区切り `,`・囲み `"`・ヘッダあり・改行 CRLF・null は空)。
 * null と空の文字列を区別するため、空の文字列は囲んで書く(`""`)。
 */
public data class CsvSettings(
    val delimiter: Char = ',',
    val quote: Char = '"',
    val header: Boolean = true,
    val lineSeparator: String = "\r\n",
    val nullValue: String = "",
) {
    public companion object {
        public val RFC4180: CsvSettings = CsvSettings()
    }
}
