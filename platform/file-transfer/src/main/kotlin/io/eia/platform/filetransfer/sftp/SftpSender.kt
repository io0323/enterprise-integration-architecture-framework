package io.eia.platform.filetransfer.sftp

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import net.schmizz.sshj.xfer.FileSystemFile
import java.nio.file.Path
import java.time.Duration

/**
 * SFTP でファイルを送る(Framework 9.2。ADR-0032 §8)。
 *
 * - 接続先はホスト鍵を事前に登録したもの([KnownHosts])だけ。暗号の方式は [SftpAlgorithms] に絞る
 * - 認証はクライアントの鍵だけ(パスワードは使わない)
 * - **一時名(`{name}.part`)で書いてから正式名に変える**(書き込み中のファイルを相手に読ませない)。正式名がすでにあれば、消してから変える
 *   (SFTP v3 の rename は上書きできないため。同じファイルの送り直しを想定)
 *
 * 鍵と known_hosts は呼び出しのたびに [SecretProvider] から読む(差し替えに追従する)。
 */
public class SftpSender(
    config: SftpConfig,
    secrets: SecretProvider,
) {
    private val connection = SftpConnection(config, secrets)

    /** [source] を [remoteDirectory]/[fileName] に送る。 */
    public fun send(
        source: Path,
        remoteDirectory: String,
        fileName: String,
    ): Result<Unit, FileTransferError> {
        if (!SftpConnection.FILE_NAME.matches(fileName)) return err(FileTransferMisuse("送るファイルの名前に使えない文字があります"))
        val directory = remoteDirectory.trimEnd('/')
        return connection.withSftp { sftp ->
            val temp = "$directory/$fileName$PART_SUFFIX"
            val target = "$directory/$fileName"
            sftp.put(FileSystemFile(source.toFile()), temp)
            if (sftp.statExistence(target) != null) sftp.rm(target)
            sftp.rename(temp, target)
        }
    }

    /**
     * [remoteDirectory]/[fileName] が相手の側にあるかを確かめる(正式名だけ。一時名の `.part` は見ない)。
     * 送り直しの前に確かめて、送信を冪等にするために使う(ADR-0032 §8)。
     */
    public fun exists(
        remoteDirectory: String,
        fileName: String,
    ): Result<Boolean, FileTransferError> {
        if (!SftpConnection.FILE_NAME.matches(fileName)) return err(FileTransferMisuse("確かめるファイルの名前に使えない文字があります"))
        val target = "${remoteDirectory.trimEnd('/')}/$fileName"
        return connection.withSftp { sftp -> sftp.statExistence(target) != null }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}

/**
 * @property knownHosts 接続先のホスト鍵(known_hosts の形。ADR-0032 §8)の秘密情報の名前。秘密ではないが、鍵と同じく差し替えるのでここから読む
 * @property privateKey クライアントの秘密鍵(OpenSSH の形)
 * @property privateKeyPassphrase 秘密鍵のパスフレーズ(なければ null)
 */
public data class SftpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val privateKey: SecretName,
    val knownHosts: SecretName,
    val privateKeyPassphrase: SecretName? = null,
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    public companion object {
        public val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        public val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
