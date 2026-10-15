package io.eia.platform.filetransfer.write

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.LocalFileFailed
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.parquet.ParquetFiles
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPOutputStream

/** ローカルに書いたデータファイル。 */
internal data class WrittenFile(
    val recordCount: Long,
    val sha256: String,
    val sizeBytes: Long,
)

/** データファイルをローカルの [target] に書く(Parquet は列ごとにまとめて書くので、置き場に直接は流さない)。 */
internal object DataFiles {
    private const val BUFFER_BYTES = 64 * 1024

    /** 行が契約に合わなければ [FileTransferMisuse](書きかけのファイルは呼び出し側が消す)。 */
    fun write(
        layout: FileLayout,
        format: FileFormat,
        rows: Sequence<List<Any?>>,
        target: Path,
    ): Result<WrittenFile, FileTransferError> =
        try {
            val count =
                when (format) {
                    FileFormat.PARQUET -> writeParquet(layout, rows, target)
                    FileFormat.CSV_GZIP -> writeCsv(layout, rows, target)
                }
            when (count) {
                is Result.Err -> count
                is Result.Ok -> ok(WrittenFile(count.value, sha256(target), Files.size(target)))
            }
        } catch (e: IOException) {
            err(LocalFileFailed(e::class.simpleName ?: "IOException"))
        }

    fun sha256(file: Path): String = Files.newInputStream(file).use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun writeParquet(
        layout: FileLayout,
        rows: Sequence<List<Any?>>,
        target: Path,
    ): Result<Long, FileTransferError> =
        ParquetFiles.writer(layout, target).use { writer ->
            var count = 0L
            for (row in rows) {
                RowCheck.problem(layout, row)?.let { return err(FileTransferMisuse("${count + 1} 行目: $it")) }
                writer.write(row)
                count++
            }
            ok(count)
        }

    private fun writeCsv(
        layout: FileLayout,
        rows: Sequence<List<Any?>>,
        target: Path,
    ): Result<Long, FileTransferError> =
        GZIPOutputStream(Files.newOutputStream(target), BUFFER_BYTES).bufferedWriter(Charsets.UTF_8).use { out ->
            CsvFiles.writeHeader(out, layout)
            var count = 0L
            for (row in rows) {
                RowCheck.problem(layout, row)?.let { return err(FileTransferMisuse("${count + 1} 行目: $it")) }
                CsvFiles.writeRow(out, layout, row)
                count++
            }
            ok(count)
        }
}
