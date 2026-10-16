@file:Suppress("MagicNumber") // テストデータの大きさと、改竄する位置

package io.eia.platform.filetransfer

import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.pgp.PgpRejection
import io.eia.platform.filetransfer.pgp.PgpVerification
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.filetransfer.sftp.SftpSender
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.SftpServerContainer
import io.eia.platform.testsupport.TestKeys
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.nio.file.Files
import java.security.PublicKey
import kotlin.random.Random

/**
 * SFTP の送信と PGP を、実際の OpenSSH のサーバ(images.env の SFTP_IMAGE)で確かめる(ADR-0032 §8)。
 * - 一時名で書いてから正式名に変える。送り直しは上書き
 * - 登録と違う鍵のホスト・登録のないホスト・古い暗号の方式しか持たないホストには接続しない
 * - 取引先(受け手)は、受け取ったファイルの署名を確かめてから復号した本文を使う。サーバの上で改竄されたら不合格
 */
class SftpIT :
    FunSpec({
        val client = TestKeys.ssh()
        val server = SftpServerContainer("eiaf-file", client.publicKey)
        // 古い方式(SHA-1 の鍵交換・ssh-rsa・CBC・hmac-sha1)しか持たないサーバ
        val legacy =
            SftpServerContainer(
                "eiaf-file",
                client.publicKey,
                extraSshdConfig =
                    """
                    KexAlgorithms diffie-hellman-group14-sha1
                    HostKeyAlgorithms ssh-rsa
                    Ciphers aes128-cbc
                    MACs hmac-sha1
                    """.trimIndent(),
            )
        val workDir = Files.createTempDirectory("sftp-it")
        val ours = TestKeys.pgp("EIAF <eiaf@example.com>")
        val partner = TestKeys.pgp("Partner <partner@example.com>")
        val sending = PgpKeys.parse(Secret(partner.publicKey), Secret(ours.secretKey), Secret(ours.passphrase)).ok()
        val receiving = PgpKeys.parse(Secret(ours.publicKey), Secret(partner.secretKey), Secret(partner.passphrase)).ok()

        fun sender(
            target: SftpServerContainer,
            knownHosts: String,
            privateKey: String = client.privateKey,
        ) = SftpSender(
            SftpConfig(target.host, target.sshPort, target.userName, SecretName("SFTP_KEY"), SecretName("SFTP_KNOWN_HOSTS")),
            EnvSecretProvider(mapOf("SFTP_KEY" to privateKey, "SFTP_KNOWN_HOSTS" to knownHosts)),
        )

        fun file(bytes: ByteArray) = Files.createTempFile(workDir, "send", ".bin").also { Files.write(it, bytes) }

        beforeSpec {
            server.start()
            legacy.start()
        }

        afterSpec {
            server.stop()
            legacy.stop()
            workDir.toFile().deleteRecursively()
        }

        test("一時名で書いてから正式名に変える。同じ名前の送り直しは上書きする。一時名は残らない") {
            val sftp = sender(server, server.knownHostsLine())
            sftp.send(file("first".toByteArray()), "/config/outbox/", "a.csv").ok()
            sftp.send(file("second".toByteArray()), "/config/outbox", "a.csv").ok()

            server.readFile("/config/outbox/a.csv")!!.decodeToString() shouldBe "second"
            server.list("/config/outbox") shouldContainExactly listOf("a.csv")
        }

        test("正式名のファイルがあるかを確かめる。一時名(.part)だけのものはないと答える。登録と違う鍵のホストには確かめに行かない") {
            val sftp = sender(server, server.knownHostsLine())
            sftp.exists("/config/outbox", "e.csv").ok() shouldBe false
            sftp.send(file("e".toByteArray()), "/config/outbox", "e.csv").ok()
            sftp.exists("/config/outbox/", "e.csv").ok() shouldBe true

            server.writeFile("/config/outbox/f.csv.part", "partial".toByteArray())
            sftp.exists("/config/outbox", "f.csv").ok() shouldBe false

            val other = TestKeys.ssh().publicKey.split(" ")[1]
            sender(server, "[${server.host}]:${server.sshPort} ssh-ed25519 $other")
                .exists("/config/outbox", "e.csv")
                .err() shouldBe RemoteRejected("HostKeyMismatch")
        }

        test("登録と違う鍵のホストには接続しない(なりすまし)。登録のないホストにも接続しない。何も書かない") {
            val other = TestKeys.ssh().publicKey.split(" ")[1]
            val wrongKey = "[${server.host}]:${server.sshPort} ssh-ed25519 $other"
            sender(server, wrongKey).send(file("x".toByteArray()), "/config/outbox", "b.csv").err() shouldBe
                RemoteRejected("HostKeyMismatch")

            val elsewhere = "[other.example.com]:22 ${server.knownHostsLine().substringAfter(' ')}"
            sender(
                server,
                elsewhere,
            ).send(file("x".toByteArray()), "/config/outbox", "b.csv").err().shouldBeInstanceOf<CredentialRejected>()
            server.readFile("/config/outbox/b.csv") shouldBe null
        }

        test("古い暗号の方式しか持たないホストには接続しない。sshj の既定の設定なら接続できる(拒否は方式の絞り込みによる)") {
            val knownHosts = legacy.knownHostsLine("rsa")
            sender(legacy, knownHosts).send(file("x".toByteArray()), "/config/outbox", "c.csv").err() shouldBe
                RemoteRejected("NoCommonAlgorithm")
            legacy.readFile("/config/outbox/c.csv") shouldBe null

            // 対照: 古い方式を含む sshj の既定の設定では、同じサーバに接続できる
            val registered = knownHosts.split(" ")[2]
            SSHClient(DefaultConfig()).use { ssh ->
                ssh.addHostKeyVerifier(
                    object : HostKeyVerifier {
                        override fun verify(
                            hostname: String,
                            port: Int,
                            key: PublicKey,
                        ): Boolean =
                            java.util.Base64
                                .getEncoder()
                                .encodeToString(
                                    net.schmizz.sshj.common.Buffer
                                        .PlainBuffer()
                                        .putPublicKey(key)
                                        .compactData,
                                ) == registered

                        override fun findExistingAlgorithms(
                            hostname: String,
                            port: Int,
                        ): List<String> = listOf("ssh-rsa")
                    },
                )
                ssh.connect(legacy.host, legacy.sshPort)
                ssh.isConnected shouldBe true
            }
        }

        test("クライアントの鍵が登録と違えば認証の失敗(リトライしない)") {
            sender(server, server.knownHostsLine(), privateKey = TestKeys.ssh().privateKey)
                .send(file("x".toByteArray()), "/config/outbox", "d.csv")
                .err() shouldBe RemoteRejected("AuthenticationFailed")
        }

        test("PGP で暗号化と署名をして送ったファイルを、取引先は署名を確かめて復号できる。サーバの上で改竄されたら不合格") {
            val plain = Random(3).nextBytes(200_000)
            val encrypted = workDir.resolve("sales.parquet.pgp")
            PgpFiles.encryptAndSign(file(plain), encrypted, sending, "sales.parquet").ok()
            sender(server, server.knownHostsLine()).send(encrypted, "/config/outbox", "sales.parquet.pgp").ok()

            // 取引先の側: 受け取ったファイルの署名を確かめてから使う
            val received = workDir.resolve("received.pgp").also { Files.write(it, server.readFile("/config/outbox/sales.parquet.pgp")!!) }
            val decrypted = workDir.resolve("decrypted.parquet")
            PgpFiles.decryptAndVerify(received, decrypted, receiving).ok() shouldBe
                PgpVerification.Verified(sending.ownFingerprint, "sales.parquet")
            Files.readAllBytes(decrypted).contentEquals(plain) shouldBe true

            // サーバの上で 1 バイト変える
            server.execInContainer(
                "sh",
                "-c",
                "printf '\\001' | dd of=/config/outbox/sales.parquet.pgp bs=1 seek=1000 conv=notrunc 2>/dev/null",
            )
            val tampered = workDir.resolve("tampered.pgp").also { Files.write(it, server.readFile("/config/outbox/sales.parquet.pgp")!!) }
            val result = PgpFiles.decryptAndVerify(tampered, workDir.resolve("tampered.parquet"), receiving).ok()
            result.shouldBeInstanceOf<PgpVerification.Rejected>().reason.let {
                (it in setOf(PgpRejection.BAD_SIGNATURE, PgpRejection.INTEGRITY_FAILED, PgpRejection.MALFORMED)) shouldBe true
            }
            Files.exists(workDir.resolve("tampered.parquet")) shouldBe false
        }
    })
