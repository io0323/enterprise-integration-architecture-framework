package io.eia.platform.testsupport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator

class TestKeysSpec :
    FunSpec({
        fun publicRing(armored: String) =
            PGPPublicKeyRing(PGPUtil.getDecoderStream(armored.byteInputStream()), JcaKeyFingerprintCalculator())

        fun secretRing(armored: String) =
            PGPSecretKeyRing(PGPUtil.getDecoderStream(armored.byteInputStream()), JcaKeyFingerprintCalculator())

        test("SSH: OpenSSH の形の秘密鍵と、authorized_keys の 1 行の公開鍵。毎回違う鍵") {
            val key = TestKeys.ssh()
            key.privateKey shouldStartWith "-----BEGIN OPENSSH PRIVATE KEY-----\n"
            key.publicKey shouldStartWith "ssh-ed25519 "
            key.toString().contains("PRIVATE") shouldBe false
            TestKeys.ssh().publicKey shouldNotBe key.publicKey
        }

        test("PGP: 署名の主鍵と暗号化の副鍵。パスフレーズで保護する") {
            val key = TestKeys.pgp("Test <test@example.com>")
            key.publicKey shouldContain "BEGIN PGP PUBLIC KEY BLOCK"
            val public = publicRing(key.publicKey)
            public.publicKeys.asSequence().count() shouldBe 2
            public.publicKeys.asSequence().count { it.isEncryptionKey && !it.isMasterKey } shouldBe 1
            secretRing(key.secretKey).secretKey.keyEncryptionAlgorithm shouldNotBe 0
            key.toString() shouldBe "TestKeys.Pgp"
        }

        test("PGP: 暗号化の鍵を持たない鍵・保護しない鍵も作れる(誤りの設定のテスト用)") {
            val signOnly = publicRing(TestKeys.pgp("S <s@example.com>", encryptionSubkey = false).publicKey)
            signOnly.publicKeys.asSequence().none { it.isEncryptionKey } shouldBe true
            secretRing(TestKeys.pgp("U <u@example.com>", protect = false).secretKey).secretKey.keyEncryptionAlgorithm shouldBe 0
        }
    })
