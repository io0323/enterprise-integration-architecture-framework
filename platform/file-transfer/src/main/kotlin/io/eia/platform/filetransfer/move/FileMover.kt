package io.eia.platform.filetransfer.move

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.MoveIncomplete
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.platform.filetransfer.verify.RejectionReason
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * 受け取ったファイルを archive / quarantine に移す(ADR-0032 §4)。S3 には名前の変更がないので、コピーしてから消す。
 *
 * 1 つのファイルは、受け口の `{prefix}{base}.` で始まるオブジェクト(データファイルとマニフェスト)。次の順に移す。
 * 1. マニフェスト以外(データファイル)を、1 つずつコピーしてから消す
 * 2. quarantine なら、理由のファイル `{base}.reason.json` を置く
 * 3. 最後にマニフェストをコピーしてから消す
 *
 * **どの手順の間で落ちても、同じ引数でもう一度呼べば、同じ結果で終わる。** 受け口にマニフェストが残る間は、ファイルの到着の起動
 * (ADR-0030 §4)がもう一度起きる。ただし、データファイルを移した後・マニフェストを移す前に落ちると、受け口にはマニフェストだけが残る。
 * このとき **検査からやり直すと、データファイルの欠損として quarantine に送ってしまう**。呼び出し側は、移動を始める前に
 * 「どちらに移すか」を記録し(file-exchange の received_file。ADR-0032 §5)、やり直しでは検査をせずに同じ移動を呼ぶ。
 */
public class FileMover(
    private val store: ObjectStore,
) {
    /**
     * 合格したファイルを [archivePrefix] に移す。終わったときに、移動先にデータファイル [dataFile] とマニフェストがあることを確かめる
     * (どちらかがなければ [MoveIncomplete]。運用に引き継ぐ)。
     */
    public fun archive(
        inboxPrefix: String,
        base: String,
        dataFile: String,
        archivePrefix: String,
    ): Result<Unit, FileTransferError> {
        if (!dataFile.startsWith("$base.")) return err(FileTransferMisuse("dataFile は $base. で始まる名前にしてください"))
        return when (val moved = move(inboxPrefix, base, archivePrefix, reason = null)) {
            is Result.Err -> moved
            is Result.Ok -> requirePresent(archivePrefix + dataFile)
        }
    }

    /** 不合格のファイルを [quarantinePrefix] に移し、理由のファイルを置く。データファイルがなければ(欠損)、マニフェストだけを移す。 */
    public fun quarantine(
        inboxPrefix: String,
        base: String,
        quarantinePrefix: String,
        reason: QuarantineReason,
    ): Result<Unit, FileTransferError> = move(inboxPrefix, base, quarantinePrefix, reason)

    @Suppress("ReturnCount") // 置き場の失敗のそれぞれで返す
    private fun move(
        fromPrefix: String,
        base: String,
        toPrefix: String,
        reason: QuarantineReason?,
    ): Result<Unit, FileTransferError> {
        if (fromPrefix == toPrefix) return err(FileTransferMisuse("移動元と移動先が同じです"))
        val manifest = base + FileName.MANIFEST_SUFFIX
        val objects =
            when (val listed = store.list("$fromPrefix$base.")) {
                is Result.Err -> return listed
                is Result.Ok -> listed.value.map { it.key.removePrefix(fromPrefix) }
            }
        for (name in objects.filter { it != manifest }) {
            moveOne(fromPrefix + name, toPrefix + name).let { if (it is Result.Err) return it }
        }
        if (reason != null) {
            store.put(toPrefix + base + REASON_SUFFIX, reason.toJson()).let { if (it is Result.Err) return it }
        }
        moveOne(fromPrefix + manifest, toPrefix + manifest).let { if (it is Result.Err) return it }
        return requirePresent(toPrefix + manifest)
    }

    /** コピーしてから消す。コピー元がなければ(前の呼び出しで移した)何もしない。 */
    private fun moveOne(
        from: String,
        to: String,
    ): Result<Unit, FileTransferError> =
        when (val copied = store.copy(from, to)) {
            is Result.Err -> copied
            is Result.Ok -> if (copied.value) store.delete(from) else ok(Unit)
        }

    private fun requirePresent(key: String): Result<Unit, FileTransferError> =
        when (val head = store.head(key)) {
            is Result.Err -> head
            is Result.Ok -> if (head.value == null) err(MoveIncomplete("移動先に $key がありません")) else ok(Unit)
        }

    public companion object {
        public const val REASON_SUFFIX: String = ".reason.json"
    }
}

/**
 * quarantine に置く理由(`{base}.reason.json`)。運用者が原因を調べ、送り手に再送を頼むときに使う。
 *
 * @property detail 固定の説明だけ(ファイルの中身の値は入れない)
 */
public data class QuarantineReason(
    val reason: RejectionReason,
    val detail: String,
    val quarantinedAt: Instant,
    val correlationId: String?,
) {
    internal fun toJson(): ByteArray =
        JSON
            .encodeToString(
                ReasonJson.serializer(),
                ReasonJson(reason.code, detail, quarantinedAt.toString(), correlationId),
            ).encodeToByteArray()

    private companion object {
        val JSON = Json { explicitNulls = false }
    }
}

@Serializable
private class ReasonJson(
    val reason: String,
    val detail: String,
    val quarantinedAt: String,
    val correlationId: String? = null,
)
