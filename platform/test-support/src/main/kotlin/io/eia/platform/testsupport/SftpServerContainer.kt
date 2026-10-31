package io.eia.platform.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.time.Duration

/**
 * SFTP のサーバのテスト用コンテナ(linuxserver/openssh-server。ADR-0016 §3)。イメージは images.env の `SFTP_IMAGE`。
 * compose と同じく、公開鍵の認証だけを許可する。
 *
 * @param publicKey 利用者の公開鍵(`ssh-ed25519 AAAA...` の 1 行)
 * @param extraSshdConfig sshd_config の末尾に足す行(暗号の方式を古いものだけにする、など)。起動時のフック(`/custom-cont-init.d`)で足す
 */
public class SftpServerContainer(
    public val userName: String,
    publicKey: String,
    extraSshdConfig: String? = null,
) : GenericContainer<SftpServerContainer>(InfraImages.get("SFTP_IMAGE")) {
    init {
        withEnv("USER_NAME", userName)
        withEnv("PUBLIC_KEY", publicKey)
        withEnv("PASSWORD_ACCESS", "false")
        withEnv("SUDO_ACCESS", "false")
        withEnv("PUID", "1000")
        withEnv("PGID", "1000")
        // 送り先のディレクトリ(compose の infra/local/sftp/10-directories.sh と同じ)。B2B の受け取りと応答のディレクトリ(P12。台帳の
        // sftp.directories の inbound・acknowledgements)も作る
        val script =
            buildString {
                appendLine("#!/usr/bin/with-contenv bash")
                val directories = "/config/outbox /config/to-eiaf /config/from-eiaf"
                appendLine("mkdir -p $directories && chown 1000:1000 $directories")
                if (extraSshdConfig != null) appendLine("printf '%s\\n' ${shellQuote(extraSshdConfig)} >> /config/sshd/sshd_config")
            }
        withCopyToContainer(Transferable.of(script, EXECUTABLE), "/custom-cont-init.d/10-test.sh")
        withExposedPorts(SSH_PORT)
        waitingFor(Wait.forLogMessage(".*\\[ls.io-init\\] done.*", 1).withStartupTimeout(STARTUP_TIMEOUT))
    }

    public val sshPort: Int get() = getMappedPort(SSH_PORT)

    /** ホストから見た接続先の known_hosts の行(`[host]:port keytype base64`)。[keyType] は `ed25519`・`rsa`・`ecdsa`。 */
    public fun knownHostsLine(keyType: String = "ed25519"): String {
        val publicKey = execInContainer("cat", "/config/ssh_host_keys/ssh_host_${keyType}_key.pub").stdout.trim().split(" ")
        return "[$host]:$sshPort ${publicKey[0]} ${publicKey[1]}"
    }

    /** コンテナの中のファイルの内容(なければ null)。 */
    public fun readFile(path: String): ByteArray? {
        val result = execInContainer("sh", "-c", "test -f '$path' && base64 -w0 '$path'")
        return if (result.exitCode == 0) {
            java.util.Base64
                .getDecoder()
                .decode(result.stdout.trim())
        } else {
            null
        }
    }

    /** コンテナの中にファイルを置く(相手の側にすでにあるファイルを用意する)。持ち主は SFTP の利用者にする。 */
    public fun writeFile(
        path: String,
        bytes: ByteArray,
    ) {
        copyFileToContainer(Transferable.of(bytes), path)
        execInContainer("chown", "1000:1000", path)
    }

    /** コンテナの中のディレクトリのファイルの名前。 */
    public fun list(directory: String): List<String> = execInContainer("ls", "-1", directory).stdout.lines().filter { it.isNotBlank() }

    private companion object {
        const val SSH_PORT = 2222
        const val EXECUTABLE = 0b111_101_101
        val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(2)

        fun shellQuote(text: String): String = text.lines().joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    }
}
