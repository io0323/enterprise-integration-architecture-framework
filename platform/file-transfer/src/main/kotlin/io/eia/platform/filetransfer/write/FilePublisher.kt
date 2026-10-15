package io.eia.platform.filetransfer.write

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.LocalFileFailed
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.manifest.FileManifest
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.time.Instant

/**
 * データファイルとマニフェストを置き場に置く(Framework 9.2。ADR-0032 §2)。
 *
 * 1. データファイルをローカルの一時ファイルに書き、件数と SHA-256 を求める
 * 2. データファイルを正式名で置く(S3 の 1 回の PUT。置き終わるまでほかからは見えない。S3 には名前の変更がないので一時名を使わない)
 * 3. 最後にマニフェストを置く。**マニフェストの到着が完了の合図**(受け側はマニフェストで起動する。ADR-0030 §4)
 *
 * 途中で落ちたら、同じ名前でもう一度呼べばよい(どちらも上書き)。マニフェストのないデータファイルは、受け側からは見えない。
 *
 * @property workDir 一時ファイルを置くディレクトリ(既定は java.io.tmpdir。distroless では /tmp)
 */
public class FilePublisher(
    private val store: ObjectStore,
    private val workDir: Path = Path(System.getProperty("java.io.tmpdir")),
) {
    /**
     * [rows] を [request] のとおりに書いて置く。行の値は [io.eia.platform.filetransfer.layout.ColumnType] の型で、列の順に並べる。
     * 行が契約に合わない・traceparent などが形式に合わなければ、何も置かずに [FileTransferMisuse]。
     */
    @Suppress("ReturnCount") // 形式の誤り・一時ファイル・書き出し・置き場の失敗のそれぞれで返す
    public fun publish(
        request: PublishRequest,
        rows: Sequence<List<Any?>>,
    ): Result<PublishedFile, FileTransferError> {
        // traceparent・Correlation ID の形式は、データファイルを書く前に確かめる(件数と SHA-256 は仮の値)
        draft(request, 0, ZERO_SHA256, null).let { checked ->
            if (checked is Result.Err) return checked
        }
        val temp =
            try {
                Files.createTempFile(workDir, "eiaf-file-", ".part")
            } catch (e: IOException) {
                return err(LocalFileFailed(e::class.simpleName ?: "IOException"))
            }
        try {
            val written =
                when (val result = DataFiles.write(request.layout, request.name.format, rows, temp)) {
                    is Result.Err -> return result
                    is Result.Ok -> result.value
                }
            val manifest =
                when (val result = draft(request, written.recordCount, written.sha256, written.sizeBytes)) {
                    is Result.Err -> return result
                    is Result.Ok -> result.value
                }
            val dataKey = request.prefix + request.name.dataFile
            val manifestKey = request.prefix + request.name.manifestFile
            store.upload(dataKey, temp).let { if (it is Result.Err) return it }
            store.put(manifestKey, manifest.toJson()).let { if (it is Result.Err) return it }
            return ok(PublishedFile(dataKey, manifestKey, manifest))
        } finally {
            try {
                temp.deleteIfExists()
            } catch (_: IOException) {
                // 一時ファイルを消せなくても、置いた結果は変わらない(作業ディレクトリの掃除は OS に任せる)
            }
        }
    }

    private fun draft(
        request: PublishRequest,
        recordCount: Long,
        sha256: String,
        sizeBytes: Long?,
    ): Result<FileManifest, FileTransferError> {
        val manifest =
            FileManifest(
                file = request.name.dataFile,
                recordCount = recordCount,
                sha256 = sha256,
                schemaVersion = request.layout.schemaVersion,
                createdAt = request.createdAt,
                traceparent = request.traceparent,
                correlationId = request.correlationId,
                sizeBytes = sizeBytes,
            )
        // 受け側と同じ検査を通す(書いたマニフェストを読めないことがないように)
        return when (val parsed = FileManifest.parse(manifest.toJson())) {
            is Result.Err -> err(FileTransferMisuse("マニフェストの形式が誤っています: ${parsed.error.message}"))
            is Result.Ok -> ok(manifest)
        }
    }

    private companion object {
        val ZERO_SHA256 = "0".repeat(64)
    }
}

/**
 * @property prefix 置き場のキーの接頭辞(例 `sales-daily/`)
 * @property traceparent 書き出した実行の span の traceparent(受け側の実行の親になる。ADR-0030 §5)
 * @property createdAt マニフェストの createdAt(UTC)
 */
public data class PublishRequest(
    val prefix: String,
    val layout: FileLayout,
    val name: FileName,
    val traceparent: String,
    val correlationId: String,
    val createdAt: Instant,
)

public data class PublishedFile(
    val dataKey: String,
    val manifestKey: String,
    val manifest: FileManifest,
)
