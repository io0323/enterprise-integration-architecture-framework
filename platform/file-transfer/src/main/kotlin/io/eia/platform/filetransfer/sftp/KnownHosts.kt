package io.eia.platform.filetransfer.sftp

import io.eia.platform.filetransfer.CredentialRejected
import io.eia.platform.filetransfer.FileTransferError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey
import java.util.Base64

/**
 * 事前に登録したホスト鍵(OpenSSH の known_hosts の形。`ssh-keyscan` の出力)で、接続先を確かめる(ADR-0032 §8)。
 * **登録のないホスト・登録と違う鍵のホストには接続しない。** 初めての接続で鍵を覚える方式(TOFU)や、検証を外す設定は作らない。
 *
 * 受け付ける行は `host keytype base64`(host は `name`・`[name]:port`・カンマ区切り)。空行と `#` の行は飛ばす。
 * ハッシュした host(`|1|`)と、マーカー(`@revoked`・`@cert-authority`)の行は、読み違いを避けるため設定の誤りにする。
 */
internal class KnownHosts private constructor(
    private val entries: List<Entry>,
) : HostKeyVerifier {
    /** 最後の検証で、登録と違う鍵を拒否したか(接続の失敗の理由の判定に使う)。 */
    @Volatile
    var rejected: Boolean = false
        private set

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean {
        val type = KeyType.fromKey(key).toString()
        val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
        val accepted = entries.any { it.matches(hostname, port) && it.keyType == type && it.key.contentEquals(encoded) }
        rejected = !accepted
        return accepted
    }

    override fun findExistingAlgorithms(
        hostname: String,
        port: Int,
    ): List<String> = entries.filter { it.matches(hostname, port) }.map { it.keyType }.distinct()

    fun hasEntryFor(
        hostname: String,
        port: Int,
    ): Boolean = entries.any { it.matches(hostname, port) }

    private class Entry(
        val hosts: List<String>,
        val keyType: String,
        val key: ByteArray,
    ) {
        fun matches(
            hostname: String,
            port: Int,
        ): Boolean {
            val name = if (port == DEFAULT_PORT) hostname else "[$hostname]:$port"
            return name in hosts
        }
    }

    companion object {
        private const val DEFAULT_PORT = 22
        private const val FIELDS = 3

        fun parse(text: String): Result<KnownHosts, FileTransferError> {
            val entries = mutableListOf<Entry>()
            for ((index, raw) in text.lines().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split(Regex("\\s+"))
                val problem =
                    when {
                        line.startsWith("@") -> "マーカー(@revoked・@cert-authority)は使えません"
                        line.startsWith("|") -> "ハッシュした host は使えません(ssh-keyscan -H を使わないでください)"
                        fields.size < FIELDS -> "host・鍵の種類・鍵の 3 つが要ります"
                        else -> null
                    }
                val key = if (problem == null) decode(fields[2]) else null
                if (problem != null || key == null) {
                    return err(CredentialRejected("known_hosts の ${index + 1} 行目: ${problem ?: "鍵を読めません"}"))
                }
                entries += Entry(fields[0].split(','), fields[1], key)
            }
            return if (entries.isEmpty()) err(CredentialRejected("known_hosts にホストがありません")) else ok(KnownHosts(entries))
        }

        private fun decode(base64: String): ByteArray? =
            try {
                Base64.getDecoder().decode(base64)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
