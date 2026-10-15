package io.eia.platform.filetransfer.sftp

import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig

/**
 * SFTP(SSH)で使う暗号の方式(ADR-0032 §8)。sshj の既定は古い方式(SHA-1 の `ssh-rsa`・`diffie-hellman-group1-sha1`・
 * `arcfour`・CBC・`hmac-md5` など)まで含むので、ここに挙げたものだけに絞る。相手がどれも持たなければ、接続しない。
 *
 * - sshj は strict kex(`kex-strict-*`。Terrapin 攻撃 CVE-2023-48795 の対策)に対応していない。そのため、Terrapin の影響を受ける
 *   `chacha20-poly1305@openssh.com` と、CTR と組み合わせた EtM の MAC(`*-etm@openssh.com`)は使わない。
 * - ホスト鍵は known_hosts で固定する([KnownHosts])ので、証明書(`*-cert-v01@openssh.com`)と FIDO の鍵(`sk-*`)は使わない。
 */
internal object SftpAlgorithms {
    /** 鍵交換。`ext-info-c` は方式ではなく、拡張の通知(RFC 8308。rsa-sha2-* の交渉に要る)。 */
    val KEY_EXCHANGE =
        listOf(
            "curve25519-sha256",
            "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp521",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp256",
            "diffie-hellman-group18-sha512",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group14-sha256",
            "ext-info-c",
        )

    /** ホスト鍵・クライアントの鍵の署名の方式。 */
    val KEY =
        listOf(
            "ssh-ed25519",
            "ecdsa-sha2-nistp521",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp256",
            "rsa-sha2-512",
            "rsa-sha2-256",
        )

    /** 暗号。 */
    val CIPHER =
        listOf(
            "aes256-gcm@openssh.com",
            "aes128-gcm@openssh.com",
            "aes256-ctr",
            "aes192-ctr",
            "aes128-ctr",
        )

    /** MAC(GCM は MAC を使わない。CTR と組み合わせるもの)。 */
    val MAC = listOf("hmac-sha2-512", "hmac-sha2-256")

    /** 許可した方式だけを持つ sshj の設定(一覧の順が優先の順)。 */
    fun config(): Config =
        DefaultConfig().apply {
            keyExchangeFactories = keyExchangeFactories.allowed(KEY_EXCHANGE) { it.name }
            keyAlgorithms = keyAlgorithms.allowed(KEY) { it.name }
            cipherFactories = cipherFactories.allowed(CIPHER) { it.name }
            macFactories = macFactories.allowed(MAC) { it.name }
        }

    private fun <T> List<T>.allowed(
        names: List<String>,
        nameOf: (T) -> String,
    ): List<T> {
        val byName = associateBy(nameOf)
        val missing = names.filterNot { it in byName }
        check(missing.isEmpty()) { "sshj に方式がありません: $missing" }
        return names.map { byName.getValue(it) }
    }
}
