package io.eia.platform.filetransfer.pgp

import io.eia.platform.filetransfer.CredentialRejected
import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.ok
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.TestKeys
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlin.random.Random

class PgpFilesSpec :
    FunSpec({
        val workDir = Files.createTempDirectory("pgp-files-spec")
        // 自社(送り手)と取引先(受け手)と、関係のない第三者
        val ours = TestKeys.pgp("EIAF <eiaf@example.com>")
        val partner = TestKeys.pgp("Partner <partner@example.com>")
        val stranger = TestKeys.pgp("Stranger <stranger@example.com>")

        afterSpec { workDir.toFile().deleteRecursively() }

        /** [own] の秘密鍵と [other] の公開鍵の組(送り手は相手の公開鍵で暗号化し、受け手は相手の公開鍵で署名を確かめる)。 */
        fun keys(
            own: TestKeys.Pgp,
            other: TestKeys.Pgp,
        ): PgpKeys = PgpKeys.parse(Secret(other.publicKey), Secret(own.secretKey), Secret(own.passphrase)).ok()

        val sending = keys(ours, partner)
        val receiving = keys(partner, ours)

        fun file(bytes: ByteArray): Path = Files.createTempFile(workDir, "plain", ".bin").also { Files.write(it, bytes) }

        fun encrypt(
            plain: ByteArray,
            keys: PgpKeys = sending,
        ): Path {
            val target = Files.createTempFile(workDir, "enc", ".pgp")
            PgpFiles.encryptAndSign(file(plain), target, keys, "sales_daily_20261014160000_001.parquet").ok()
            return target
        }

        fun decrypt(
            encrypted: Path,
            keys: PgpKeys = receiving,
        ): Pair<PgpVerification, Path> {
            val target = workDir.resolve("dec-${Random.nextLong()}.bin")
            return PgpFiles.decryptAndVerify(encrypted, target, keys).ok() to target
        }

        test("自社の鍵で署名し、取引先の公開鍵で暗号化したファイルを、取引先は復号して署名を確かめられる(数 MB を流しながら)") {
            val plain = Random(1).nextBytes(3 * 1024 * 1024 + 7)
            val encrypted = encrypt(plain)
            Files.readAllBytes(encrypted).contentEquals(plain) shouldBe false

            val (result, target) = decrypt(encrypted)
            result shouldBe PgpVerification.Verified(sending.ownFingerprint, "sales_daily_20261014160000_001.parquet")
            Files.readAllBytes(target).contentEquals(plain) shouldBe true
            receiving.partnerFingerprint shouldBe sending.ownFingerprint
        }

        test("署名の鍵の差し替えの間は、旧い鍵の署名も受け付ける。結果は実際に署名した鍵。旧い鍵を外せば、旧い鍵の署名は不合格") {
            val renewed = TestKeys.pgp("EIAF renewed <eiaf@example.com>")
            // 取引先(受け手)の側から見て、自社の今の鍵は renewed、旧い鍵は ours
            val rotating =
                PgpKeys
                    .parse(
                        Secret(renewed.publicKey),
                        Secret(partner.secretKey),
                        Secret(partner.passphrase),
                        listOf(Secret(ours.publicKey)),
                    ).ok()
            val afterRotation = keys(partner, renewed)

            val (byOld, _) = decrypt(encrypt("old".encodeToByteArray()), rotating)
            byOld.shouldBeInstanceOf<PgpVerification.Verified>().signerFingerprint shouldBe keys(partner, ours).partnerFingerprint
            val (byNew, _) = decrypt(encrypt("new".encodeToByteArray(), keys(renewed, partner)), rotating)
            byNew.shouldBeInstanceOf<PgpVerification.Verified>().signerFingerprint shouldBe afterRotation.partnerFingerprint
            decrypt(encrypt("old".encodeToByteArray()), afterRotation).first shouldBe PgpVerification.Rejected(PgpRejection.UNKNOWN_SIGNER)
            PgpKeys
                .parse(
                    Secret(renewed.publicKey),
                    Secret(partner.secretKey),
                    Secret(partner.passphrase),
                    listOf(Secret("x")),
                ).err() shouldBe
                CredentialRejected("取引先の旧い公開鍵を読めません")
        }

        test("改竄した暗号文は不合格。書き出しかけた本文は消す") {
            val encrypted = encrypt(Random(2).nextBytes(100_000))
            val bytes = Files.readAllBytes(encrypted)
            for (position in listOf(bytes.size / 2, bytes.size - 30)) {
                val tampered = bytes.copyOf().also { it[position] = (it[position] + 1).toByte() }
                val (result, target) = decrypt(file(tampered))
                result.shouldBeInstanceOf<PgpVerification.Rejected>()
                Files.exists(target) shouldBe false
            }
        }

        test("取引先の鍵でない鍵の署名(なりすまし)・自社あてでない暗号文・PGP でないファイルは不合格") {
            // 第三者が、取引先(受け手)あてに暗号化して自分の鍵で署名した
            val forged = encrypt("forged".toByteArray(), keys(stranger, partner))
            decrypt(forged).first shouldBe PgpVerification.Rejected(PgpRejection.UNKNOWN_SIGNER)

            // 第三者あてに暗号化したもの
            val notForUs = encrypt("x".toByteArray(), keys(ours, stranger))
            decrypt(notForUs).first shouldBe PgpVerification.Rejected(PgpRejection.NOT_FOR_US)

            decrypt(file("not pgp at all".toByteArray())).first.shouldBeInstanceOf<PgpVerification.Rejected>()
            decrypt(file(ByteArray(0))).first.shouldBeInstanceOf<PgpVerification.Rejected>()
        }

        test("署名のない暗号文・改竄の検知(MDC)のない古い形式は不合格") {
            /** 取引先(受け手)あてに、署名をせずに暗号化する。[integrity] が false なら MDC のない古い形式(SED)。 */
            fun unsigned(integrity: Boolean): Path {
                val target = Files.createTempFile(workDir, "unsigned", ".pgp")
                Files.newOutputStream(target).use { out ->
                    val generator =
                        PGPEncryptedDataGenerator(
                            JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                                .setWithIntegrityPacket(integrity)
                                .setProvider(PgpKeys.PROVIDER),
                        ).apply {
                            addMethod(
                                JcePublicKeyKeyEncryptionMethodGenerator(sending.partnerEncryptionKey).setProvider(PgpKeys.PROVIDER),
                            )
                        }
                    generator.open(out, ByteArray(4096)).use { encrypted ->
                        PGPLiteralDataGenerator().open(encrypted, PGPLiteralData.BINARY, "x", Date(), ByteArray(4096)).use {
                            it.write("no signature".toByteArray())
                        }
                    }
                }
                return target
            }

            decrypt(unsigned(integrity = true)).first shouldBe PgpVerification.Rejected(PgpRejection.UNSIGNED)
            decrypt(unsigned(integrity = false)).first shouldBe PgpVerification.Rejected(PgpRejection.NO_INTEGRITY)
        }

        test("鍵の誤り: パスフレーズが違う・保護されていない秘密鍵・暗号化の鍵がない公開鍵・読めない鍵。理由に鍵の値は入れない") {
            fun parse(
                public: String,
                secret: String,
                passphrase: String,
            ) = PgpKeys.parse(Secret(public), Secret(secret), Secret(passphrase)).err().shouldBeInstanceOf<CredentialRejected>()

            parse(partner.publicKey, ours.secretKey, "wrong").reason shouldContain "パスフレーズが違います"
            val unprotected = TestKeys.pgp("U <u@example.com>", protect = false)
            parse(partner.publicKey, unprotected.secretKey, "x").reason shouldContain "パスフレーズで保護されていません"
            val signOnly = TestKeys.pgp("S <s@example.com>", encryptionSubkey = false)
            parse(signOnly.publicKey, ours.secretKey, ours.passphrase).reason shouldContain "暗号化の鍵がありません"
            parse("garbage", ours.secretKey, ours.passphrase).reason shouldContain "取引先の公開鍵を読めません"
            parse(partner.publicKey, "garbage", ours.passphrase).message.contains(ours.passphrase) shouldBe false
        }

        test("鍵は SecretProvider から読む(_FILE も使える)。ないときは設定の誤り") {
            val secretFile = Files.createTempFile(workDir, "secret", ".asc").also { Files.writeString(it, ours.secretKey) }
            val names = PgpKeyNames(SecretName("PARTNER_PUBLIC"), SecretName("OWN_SECRET"), SecretName("OWN_PASSPHRASE"))
            val provider =
                EnvSecretProvider(
                    mapOf(
                        "PARTNER_PUBLIC" to partner.publicKey,
                        "OWN_SECRET_FILE" to secretFile.toString(),
                        "OWN_PASSPHRASE" to ours.passphrase,
                    ),
                )
            PgpKeys.load(provider, names).ok().ownFingerprint shouldBe sending.ownFingerprint
            PgpKeys
                .load(EnvSecretProvider(emptyMap()), names)
                .err()
                .shouldBeInstanceOf<CredentialRejected>()
                .reason shouldContain
                "PARTNER_PUBLIC"
            sending.toString() shouldContain sending.partnerFingerprint
        }
    })
