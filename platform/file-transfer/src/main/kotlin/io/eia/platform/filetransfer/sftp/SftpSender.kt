package io.eia.platform.filetransfer.sftp

import io.eia.platform.filetransfer.CredentialRejected
import io.eia.platform.filetransfer.CredentialUnavailable
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.RemoteRejected
import io.eia.platform.filetransfer.RemoteUnavailable
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.IOException
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
    private val config: SftpConfig,
    private val secrets: SecretProvider,
) {
    /** [source] を [remoteDirectory]/[fileName] に送る。 */
    @Suppress("ReturnCount") // 名前の誤り・鍵の読み込みの失敗で、接続の前に返す
    public fun send(
        source: Path,
        remoteDirectory: String,
        fileName: String,
    ): Result<Unit, FileTransferError> {
        if (!FILE_NAME.matches(fileName)) return err(FileTransferMisuse("送るファイルの名前に使えない文字があります"))
        val knownHostsText =
            when (val read = secret(config.knownHosts)) {
                is Result.Err -> return read
                is Result.Ok -> read.value
            }
        val knownHosts =
            when (val parsed = KnownHosts.parse(knownHostsText.reveal())) {
                is Result.Err -> return parsed
                is Result.Ok -> parsed.value
            }
        if (!knownHosts.hasEntryFor(config.host, config.port)) {
            return err(CredentialRejected("known_hosts に ${config.host}:${config.port} の登録がありません"))
        }
        val privateKey =
            when (val read = secret(config.privateKey)) {
                is Result.Err -> return read
                is Result.Ok -> read.value
            }
        val passphrase =
            config.privateKeyPassphrase?.let { name ->
                when (val read = secret(name)) {
                    is Result.Err -> return read
                    is Result.Ok -> read.value
                }
            }
        val directory = remoteDirectory.trimEnd('/')
        return withSftp(knownHosts, privateKey, passphrase) { sftp ->
            val temp = "$directory/$fileName$PART_SUFFIX"
            val target = "$directory/$fileName"
            sftp.put(FileSystemFile(source.toFile()), temp)
            if (sftp.statExistence(target) != null) sftp.rm(target)
            sftp.rename(temp, target)
        }
    }

    private fun withSftp(
        knownHosts: KnownHosts,
        privateKey: Secret,
        passphrase: Secret?,
        block: (SFTPClient) -> Unit,
    ): Result<Unit, FileTransferError> =
        try {
            SSHClient(SftpAlgorithms.config()).use { ssh ->
                ssh.connectTimeout = config.connectTimeout.toMillis().toInt()
                ssh.timeout = config.timeout.toMillis().toInt()
                ssh.addHostKeyVerifier(knownHosts)
                ssh.connect(config.host, config.port)
                val keys =
                    ssh.loadKeys(
                        privateKey.reveal(),
                        null,
                        passphrase?.let { PasswordUtils.createOneOff(it.reveal().toCharArray()) },
                    )
                ssh.authPublickey(config.username, keys)
                ssh.newSFTPClient().use(block)
            }
            ok(Unit)
        } catch (_: UserAuthException) {
            err(RemoteRejected("AuthenticationFailed"))
        } catch (e: TransportException) {
            err(transportFailure(e, knownHosts))
        } catch (e: SFTPException) {
            err(
                when (e.statusCode) {
                    Response.StatusCode.PERMISSION_DENIED -> RemoteRejected("PermissionDenied")
                    Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> RemoteRejected("NoSuchPath")
                    else -> RemoteUnavailable("SFTP ${e.statusCode}")
                },
            )
        } catch (e: IOException) {
            err(RemoteUnavailable(e::class.simpleName ?: "IOException"))
        }

    private fun transportFailure(
        e: TransportException,
        knownHosts: KnownHosts,
    ): FileTransferError =
        when {
            knownHosts.rejected || e.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE -> {
                RemoteRejected("HostKeyMismatch")
            }

            // sshj は方式の交渉の失敗を、理由を付けずに「Unable to reach a settlement」の例外で知らせる
            e.disconnectReason == DisconnectReason.KEY_EXCHANGE_FAILED || causes(e).any { NEGOTIATION_FAILED in it.message.orEmpty() } -> {
                RemoteRejected("NoCommonAlgorithm")
            }

            else -> {
                RemoteUnavailable("Transport ${e.disconnectReason}")
            }
        }

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSES)

    private fun secret(name: SecretName): Result<Secret, FileTransferError> =
        when (val got = secrets.get(name)) {
            is Result.Ok -> {
                got
            }

            is Result.Err -> {
                err(
                    if (got.error is DomainError.Retryable) {
                        CredentialUnavailable(
                            got.error.code,
                        )
                    } else {
                        CredentialRejected("$name: ${got.error.code}")
                    },
                )
            }
        }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val NEGOTIATION_FAILED = "Unable to reach a settlement"
        const val MAX_CAUSES = 8
        val FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,200}$")
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
