package io.eia.platform.filetransfer.sftp

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.RemoteRejected
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * SFTP でファイルを受け取る(取引先が置いた文書。P12 ④b1。ADR-0040 §4)。接続は [SftpSender] と同じ([SftpConnection])。
 * - 一覧([list])は通常のファイルだけ(ディレクトリ・リンクは返さない)
 * - 押さえ・処理済みへの移動は、同じサーバの中の名前の変更([rename])。名前の変更はサーバの中で不可分なので、2 つの処理が同じファイルを
 *   押さえることはない(変更の元がなければ false)
 * - 読み出し([read])は最大の大きさを決めて、メモリに読む(B2B の文書は小さい。超えたら [RemoteRejected])
 */
public class SftpReceiver(
    config: SftpConfig,
    secrets: SecretProvider,
) {
    private val connection = SftpConnection(config, secrets)

    /** [directory] の通常のファイル。ディレクトリがなければ空。 */
    public fun list(directory: String): Result<List<RemoteFile>, FileTransferError> =
        connection.withSftp { sftp ->
            if (sftp.statExistence(directory) == null) {
                emptyList()
            } else {
                sftp
                    .ls(directory)
                    .filter { it.attributes.type == FileMode.Type.REGULAR && SftpConnection.FILE_NAME.matches(it.name) }
                    .map { RemoteFile(it.name, it.attributes.size, Instant.ofEpochSecond(it.attributes.mtime)) }
                    .sortedBy { it.name }
            }
        }

    /** [directory]/[name] を読む。[maxBytes] を超えたら [RemoteRejected]("TooLarge")。 */
    public fun read(
        directory: String,
        name: String,
        maxBytes: Long,
    ): Result<ByteArray, FileTransferError> {
        if (!SftpConnection.FILE_NAME.matches(name)) return err(FileTransferMisuse("読むファイルの名前に使えない文字があります"))
        val path = "${directory.trimEnd('/')}/$name"
        return connection.withSftp { sftp -> readAll(sftp, path, maxBytes) }.let { result ->
            if (result is Result.Ok && result.value == null) err(RemoteRejected("TooLarge")) else result.mapNotNull()
        }
    }

    /**
     * [fromDirectory]/[name] を [toDirectory]/[toName] に変える(押さえ・処理済みへの移動)。変更の先のディレクトリがなければ作る。
     * 変更の元がなければ false(ほかの処理が先に押さえた・移した)。変更の先がすでにあれば [RemoteRejected](SFTP v3 の rename は上書きしない)。
     */
    public fun rename(
        fromDirectory: String,
        name: String,
        toDirectory: String,
        toName: String = name,
    ): Result<Boolean, FileTransferError> {
        if (!SftpConnection.FILE_NAME.matches(name) || !SftpConnection.FILE_NAME.matches(toName)) {
            return err(FileTransferMisuse("名前を変えるファイルの名前に使えない文字があります"))
        }
        val source = "${fromDirectory.trimEnd('/')}/$name"
        val target = "${toDirectory.trimEnd('/')}/$toName"
        return connection.withSftp { sftp ->
            if (sftp.statExistence(source) == null) {
                false
            } else {
                if (sftp.statExistence(toDirectory) == null) sftp.mkdirs(toDirectory)
                sftp.rename(source, target)
                true
            }
        }
    }

    /** 最大 [maxBytes] まで読む。超えたら null。 */
    private fun readAll(
        sftp: SFTPClient,
        path: String,
        maxBytes: Long,
    ): ByteArray? =
        sftp.open(path, setOf(OpenMode.READ)).use { file ->
            if (file.length() > maxBytes) return null
            val out = ByteArrayOutputStream()
            file.RemoteFileInputStream().use { it.copyTo(out) }
            out.toByteArray()
        }

    private fun Result<ByteArray?, FileTransferError>.mapNotNull(): Result<ByteArray, FileTransferError> =
        when (this) {
            is Result.Ok -> Result.Ok(checkNotNull(value))
            is Result.Err -> this
        }
}

/** 受け取りのディレクトリのファイル。[modifiedAt] は更新の時刻(秒の精度。書き終わりの判断 `stable` に使う)。 */
public data class RemoteFile(
    val name: String,
    val size: Long,
    val modifiedAt: Instant,
)
