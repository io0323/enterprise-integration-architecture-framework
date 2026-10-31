package io.eia.platform.testsupport

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import java.util.Date

/** テスト用の鍵(SSH のクライアントの鍵と、PGP の鍵)を作る。テストのたびに作り、どこにも保存しない。 */
public object TestKeys {
    private val provider = BouncyCastleProvider()
    private const val RSA_BITS = 2048
    private const val PEM_LINE = 70

    /** OpenSSH の形の秘密鍵(PEM)と、`authorized_keys` の 1 行の公開鍵。 */
    public class Ssh(
        public val privateKey: String,
        public val publicKey: String,
    ) {
        override fun toString(): String = "TestKeys.Ssh(publicKey=$publicKey)"
    }

    /** ASCII armor の PGP の鍵。 */
    public class Pgp(
        public val publicKey: String,
        public val secretKey: String,
        public val passphrase: String,
    ) {
        override fun toString(): String = "TestKeys.Pgp"
    }

    /** Ed25519 のクライアントの鍵(パスフレーズなし)。 */
    public fun ssh(): Ssh {
        val generator = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val pair = generator.generateKeyPair()
        val body = Base64.getEncoder().encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private))
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n${body.chunked(PEM_LINE).joinToString("\n")}\n-----END OPENSSH PRIVATE KEY-----\n"
        val public = "ssh-ed25519 ${Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pair.public))} eiaf-test"
        return Ssh(pem, public)
    }

    /**
     * RSA の主鍵(署名)と、暗号化の副鍵を持つ PGP の鍵(テストを速くするため 2048 ビット)。
     *
     * @param encryptionSubkey false なら暗号化に使える鍵を持たない(主鍵は署名だけの RSA)
     * @param protect false ならパスフレーズで保護しない
     * @param masterExpiry 主鍵の有効期間(秒。null なら期限なし)
     * @param subkeyExpiry 暗号化の副鍵の有効期間(秒。null なら期限なし)
     */
    @Suppress("LongParameterList") // 鍵の作り方の条件を、既定値つきの名前付きの引数で並べる
    public fun pgp(
        userId: String,
        passphrase: String = "test-passphrase",
        encryptionSubkey: Boolean = true,
        protect: Boolean = true,
        masterExpiry: Long? = null,
        subkeyExpiry: Long? = null,
    ): Pgp {
        val rsa = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(RSA_BITS) }
        val now = Date()
        val masterAlgorithm = if (encryptionSubkey) PublicKeyAlgorithmTags.RSA_GENERAL else PublicKeyAlgorithmTags.RSA_SIGN
        val master = JcaPGPKeyPair(masterAlgorithm, rsa.generateKeyPair(), now)
        val sha1 = JcaPGPDigestCalculatorProviderBuilder().setProvider(provider).build().get(HashAlgorithmTags.SHA1)
        val masterFlags =
            PGPSignatureSubpacketGenerator().apply {
                setKeyFlags(false, KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER)
                masterExpiry?.let { setKeyExpirationTime(false, it) }
            }
        val encryptor =
            if (protect) {
                JcePBESecretKeyEncryptorBuilder(
                    SymmetricKeyAlgorithmTags.AES_256,
                    sha1,
                ).setProvider(provider).build(passphrase.toCharArray())
            } else {
                null
            }
        val ring =
            PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                master,
                userId,
                sha1,
                masterFlags.generate(),
                null,
                JcaPGPContentSignerBuilder(master.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(provider),
                encryptor,
            )
        if (encryptionSubkey) {
            val flags =
                PGPSignatureSubpacketGenerator().apply {
                    setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
                    subkeyExpiry?.let { setKeyExpirationTime(false, it) }
                }
            ring.addSubKey(JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, rsa.generateKeyPair(), now), flags.generate(), null)
        }
        return Pgp(armor { ring.generatePublicKeyRing().encode(it) }, armor { ring.generateSecretKeyRing().encode(it) }, passphrase)
    }

    private fun armor(write: (ArmoredOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream()
        ArmoredOutputStream(bytes).use(write)
        return bytes.toString(Charsets.US_ASCII)
    }
}
