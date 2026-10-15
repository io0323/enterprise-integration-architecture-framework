package io.eia.platform.filetransfer.sftp

import io.eia.platform.filetransfer.CredentialRejected
import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import net.schmizz.sshj.common.Buffer
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64

class KnownHostsSpec :
    FunSpec({
        fun key(): PublicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public

        fun line(
            hosts: String,
            key: PublicKey,
        ) = "$hosts ssh-ed25519 ${Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData)}"

        test("登録した host と鍵だけを受け付ける。ポートが 22 以外は [host]:port で登録する") {
            val registered = key()
            val known =
                KnownHosts
                    .parse(
                        """
                        # comment

                        ${line("sftp.example.com,10.0.0.5", registered)}
                        ${line("[localhost]:2222", registered)}
                        """.trimIndent(),
                    ).ok()

            known.verify("sftp.example.com", 22, registered) shouldBe true
            known.verify("10.0.0.5", 22, registered) shouldBe true
            known.verify("localhost", 2222, registered) shouldBe true
            known.rejected shouldBe false

            known.verify("sftp.example.com", 22, key()) shouldBe false
            known.rejected shouldBe true
            known.verify("localhost", 22, registered) shouldBe false
            known.verify("other.example.com", 22, registered) shouldBe false

            known.findExistingAlgorithms("localhost", 2222) shouldContainExactly listOf("ssh-ed25519")
            known.hasEntryFor("other.example.com", 22) shouldBe false
        }

        test("ハッシュした host・マーカー・欠けた行・読めない鍵・空は設定の誤り") {
            fun problem(text: String) =
                KnownHosts
                    .parse(text)
                    .err()
                    .shouldBeInstanceOf<CredentialRejected>()
                    .reason

            problem("|1|abc= ssh-ed25519 AAAA") shouldContain "ハッシュ"
            problem("@revoked host ssh-ed25519 AAAA") shouldContain "マーカー"
            problem("host ssh-ed25519") shouldContain "3 つ"
            problem("host ssh-ed25519 !!!") shouldContain "鍵を読めません"
            problem("# only comment\n") shouldContain "ホストがありません"
        }
    })
