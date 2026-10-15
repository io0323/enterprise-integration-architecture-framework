package io.eia.platform.filetransfer.layout

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** データファイルの形式と拡張子(契約の formats[])。 */
public enum class FileFormat(
    public val extension: String,
) {
    /** Parquet(zstd。ADR-0033) */
    PARQUET("parquet"),

    /** CSV(CsvSettings)をファイル全体で gzip にしたもの */
    CSV_GZIP("csv.gz"),
    ;

    public companion object {
        /** データファイルの名前の拡張子から形式を選ぶ(受け側。契約の「受ける側は manifest の file の拡張子で形式を選ぶ」)。 */
        public fun ofFileName(fileName: String): FileFormat? = entries.firstOrNull { fileName.endsWith(".${it.extension}") }
    }
}

/**
 * データファイルの名前 `{system}_{dataset}_{yyyyMMddHHmmss}_{seq}.{ext}`(Framework 9.2・INTEGRATION_STANDARDS §1)。
 * マニフェストは `{base}.manifest.json`。時刻は UTC で書く。
 *
 * @property base 拡張子を除いた名前(platform/batch のファイルの到着のパーティションのキー)
 */
public data class FileName(
    val base: String,
    val format: FileFormat,
) {
    public val dataFile: String get() = "$base.${format.extension}"
    public val manifestFile: String get() = "$base$MANIFEST_SUFFIX"

    public companion object {
        public const val MANIFEST_SUFFIX: String = ".manifest.json"
        private const val MAX_SEQ = 999_999
        private const val SEQ_DIGITS = 3
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

        public fun of(
            layout: FileLayout,
            createdAt: Instant,
            seq: Int,
            format: FileFormat,
        ): Result<FileName, FileTransferError> {
            if (seq !in 1..MAX_SEQ) return err(FileTransferMisuse("seq は 1〜$MAX_SEQ にしてください"))
            val base = "${layout.system}_${layout.dataset}_${STAMP.format(
                createdAt.toJavaInstant(),
            )}_${seq.toString().padStart(SEQ_DIGITS, '0')}"
            return ok(FileName(base, format))
        }
    }
}
