package io.eia.platform.filetransfer.sftp

import io.eia.platform.filetransfer.CredentialRejected
import io.eia.platform.filetransfer.CredentialUnavailable
import io.eia.platform.filetransfer.FileTransferError
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
import java.io.IOException

/**
 * SFTP の接続(送信の [SftpSender] と受け取りの [SftpReceiver] で共有する。ADR-0032 §8)。
 * 接続先はホスト鍵を事前に登録したもの([KnownHosts])だけ、暗号の方式は [SftpAlgorithms] に絞り、認証はクライアントの鍵だけ。
 * 鍵と known_hosts は呼び出しのたびに [SecretProvider] から読む(差し替えに追従する)。失敗の分け方は ADR-0032 §8 のとおり。
 */
internal class SftpConnection(
    private val config: SftpConfig,
    private val secrets: SecretProvider,
) {
    @Suppress("ReturnCount") // 鍵の読み込みの失敗で、接続の前に返す
    fun <T> withSftp(block: (SFTPClient) -> T): Result<T, FileTransferError> {
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
        return connect(knownHosts, privateKey, passphrase, block)
    }

    private fun <T> connect(
        knownHosts: KnownHosts,
        privateKey: Secret,
        passphrase: Secret?,
        block: (SFTPClient) -> T,
    ): Result<T, FileTransferError> =
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
                ok(ssh.newSFTPClient().use(block))
            }
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

    companion object {
        const val NEGOTIATION_FAILED = "Unable to reach a settlement"
        const val MAX_CAUSES = 8

        /** 送受信するファイルの名前(ディレクトリの区切りや先頭の . を含まない)。 */
        val FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,200}$")
    }
}
