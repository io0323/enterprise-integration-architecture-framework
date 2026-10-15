package io.eia.platform.filetransfer.verify

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.LocalFileFailed
import io.eia.platform.filetransfer.ObjectMissing
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.parquet.FooterResult
import io.eia.platform.filetransfer.parquet.ParquetFiles
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.platform.filetransfer.write.CsvFiles
import io.eia.platform.filetransfer.write.DataFiles
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists

/**
 * 受け取ったファイルを検査する(Framework 9.2「SHA-256 をマニフェストに記載し受信側で必ず検証」。ADR-0032 §3)。
 *
 * 順に、マニフェストの形式 → 名前の対応 → スキーマの版 → 形式(拡張子) → データファイルの有無 → 大きさ → SHA-256 →
 * 中身(Parquet はフッタの列と件数、CSV はヘッダとレコードの数)を見て、最初に見つかった不合格の理由を返す。
 * データファイルはローカルの一時ファイルに取ってから調べる(Parquet のフッタはファイルの末尾にあるため)。
 *
 * 置き場を読めない・一時ファイルを書けないときは失敗([FileTransferError])で、ファイルの不合格とは区別する。
 *
 * @property layouts 受け付けるスキーマの版(契約)
 */
public class FileVerifier(
    private val store: ObjectStore,
    layouts: Collection<FileLayout>,
    private val workDir: Path = Path(System.getProperty("java.io.tmpdir")),
) {
    private val layouts: Map<String, FileLayout> = layouts.associateBy { it.schemaVersion }

    @Suppress("ReturnCount") // キーの誤り・置き場の失敗・マニフェストがない・形式の誤りのそれぞれで返す
    public fun verify(
        prefix: String,
        manifestKey: String,
    ): Result<Verification, FileTransferError> {
        if (!manifestKey.startsWith(prefix) || !manifestKey.endsWith(FileName.MANIFEST_SUFFIX)) {
            return err(FileTransferMisuse("マニフェストのキーは $prefix の下の *${FileName.MANIFEST_SUFFIX} にしてください"))
        }
        val base = manifestKey.removePrefix(prefix).removeSuffix(FileName.MANIFEST_SUFFIX)
        val bytes =
            when (val read = store.read(manifestKey, FileManifest.MAX_BYTES)) {
                is Result.Err -> return read
                is Result.Ok -> read.value ?: return err(ObjectMissing(manifestKey))
            }
        return when (val parsed = FileManifest.parse(bytes)) {
            is Result.Err -> ok(Verification.Rejected(base, RejectionReason.MANIFEST_INVALID, parsed.error.message, null))
            is Result.Ok -> inspect(prefix, base, parsed.value)
        }
    }

    /** マニフェストの中身(名前の対応・スキーマの版・形式)を見てから、データファイルを調べる。 */
    private fun inspect(
        prefix: String,
        base: String,
        manifest: FileManifest,
    ): Result<Verification, FileTransferError> {
        fun rejected(
            reason: RejectionReason,
            detail: String,
        ): Result<Verification, FileTransferError> = ok(Verification.Rejected(base, reason, detail, manifest))

        val layout = layouts[manifest.schemaVersion]
        val format = FileFormat.ofFileName(manifest.file)
        return when {
            !manifest.file.startsWith("$base.") -> {
                rejected(RejectionReason.NAME_MISMATCH, "データファイルの名前がマニフェストの名前と対応しません")
            }

            layout == null -> {
                rejected(RejectionReason.SCHEMA_VERSION_UNKNOWN, "受け付けていないスキーマの版です")
            }

            format == null -> {
                rejected(RejectionReason.FORMAT_UNSUPPORTED, "拡張子が契約の形式にありません")
            }

            else -> {
                withTempFile { temp ->
                    val dataKey = prefix + manifest.file
                    when (val downloaded = store.download(dataKey, temp)) {
                        is Result.Err -> {
                            downloaded
                        }

                        is Result.Ok -> {
                            if (!downloaded.value) {
                                rejected(RejectionReason.DATA_MISSING, "データファイルがありません")
                            } else {
                                val problem = checkContent(manifest, layout, format, temp)
                                if (problem ==
                                    null
                                ) {
                                    ok(Verification.Accepted(base, manifest, dataKey))
                                } else {
                                    rejected(problem.first, problem.second)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 作業用の一時ファイルを作って [block] に渡し、最後に消す。一時ファイルを読み書きできなければ [LocalFileFailed]。 */
    private fun withTempFile(block: (Path) -> Result<Verification, FileTransferError>): Result<Verification, FileTransferError> {
        val temp =
            try {
                Files.createTempFile(workDir, "eiaf-verify-", ".part")
            } catch (e: IOException) {
                return err(LocalFileFailed(e::class.simpleName ?: "IOException"))
            }
        return try {
            block(temp)
        } catch (e: IOException) {
            err(LocalFileFailed(e::class.simpleName ?: "IOException"))
        } finally {
            try {
                temp.deleteIfExists()
            } catch (_: IOException) {
                // 消せなくても、検査の結果は変わらない
            }
        }
    }

    /** データファイルの中身の不合格の理由と説明。合格なら null。 */
    @Suppress("ReturnCount") // 大きさ → SHA-256 → 中身の順に、最初の不合格で返す
    private fun checkContent(
        manifest: FileManifest,
        layout: FileLayout,
        format: FileFormat,
        file: Path,
    ): Pair<RejectionReason, String>? {
        val size = Files.size(file)
        if (manifest.sizeBytes != null && manifest.sizeBytes != size) {
            return RejectionReason.SIZE_MISMATCH to "データファイルの大きさがマニフェストと違います"
        }
        if (DataFiles.sha256(file) != manifest.sha256) {
            return RejectionReason.CHECKSUM_MISMATCH to "SHA-256 がマニフェストと違います"
        }
        val (records, schemaProblem) =
            when (format) {
                FileFormat.PARQUET -> parquet(layout, file)
                FileFormat.CSV_GZIP -> csv(layout, file)
            } ?: return RejectionReason.CONTENT_UNREADABLE to "データファイルを形式どおりに読めません"
        if (schemaProblem != null) return RejectionReason.SCHEMA_MISMATCH to schemaProblem
        if (records != manifest.recordCount) return RejectionReason.RECORD_COUNT_MISMATCH to "件数がマニフェストと違います"
        return null
    }

    /** 件数と、列が契約と違う理由(なければ null)。読めなければ null。 */
    private fun parquet(
        layout: FileLayout,
        file: Path,
    ): Pair<Long, String?>? =
        when (val footer = ParquetFiles.readFooter(file)) {
            is FooterResult.Unreadable -> {
                null
            }

            is FooterResult.Read -> {
                val mismatches = ParquetFiles.mismatches(layout, footer.footer)
                footer.footer.rowCount to mismatches.takeIf { it.isNotEmpty() }?.let { "列が契約と違います: ${it.joinToString()}" }
            }
        }

    private fun csv(
        layout: FileLayout,
        file: Path,
    ): Pair<Long, String?>? {
        val count =
            try {
                GZIPInputStream(Files.newInputStream(file)).use { CsvFiles.count(it, layout.csv) }
            } catch (_: ZipException) {
                null
            } catch (_: EOFException) {
                null
            }
        val expected = layout.columns.map { it.name }
        return count?.takeUnless { it.malformed }?.let {
            it.records to if (it.header != null && it.header != expected) "ヘッダの列が契約と違います" else null
        }
    }
}
