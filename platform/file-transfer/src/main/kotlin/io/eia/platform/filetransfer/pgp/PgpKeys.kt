package io.eia.platform.filetransfer.pgp

import io.eia.platform.filetransfer.CredentialRejected
import io.eia.platform.filetransfer.CredentialUnavailable
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import java.io.IOException
import java.security.Provider
import java.time.Instant
import java.util.HexFormat

/**
 * 取引先とのファイルの PGP の鍵(ADR-0032 §8)。
 * - 取引先の公開鍵: 送るファイルの暗号化と、受け取ったファイルの署名の検証に使う
 * - 自社の秘密鍵(とパスフレーズ): 送るファイルの署名と、受け取ったファイルの復号に使う
 *
 * 鍵は ASCII armor の文字列で、[SecretProvider] から読む(`NAME_FILE` で Docker secrets のファイルを渡せる)。
 * 秘密鍵はパスフレーズで保護されたものだけを受け付ける。読み込み時に、用途の鍵(暗号化・署名)がそろっているかと、パスフレーズを確かめる。
 */
public class PgpKeys private constructor(
    internal val partner: PGPPublicKeyRing,
    internal val own: PGPSecretKeyRing,
    private val passphrase: CharArray,
) {
    /** 取引先の主鍵のフィンガープリント(16 進の大文字。ログと運用の照合に使う)。 */
    public val partnerFingerprint: String get() = fingerprint(partner.publicKey)

    /** 自社の主鍵のフィンガープリント。 */
    public val ownFingerprint: String get() = fingerprint(own.publicKey)

    /** 取引先の暗号化の鍵(暗号化に使えて、失効していないもの。副鍵を優先)。 */
    internal val partnerEncryptionKey: PGPPublicKey get() = encryptionKey(partner.publicKeys.asSequence())!!

    /** 自社の署名の鍵(署名に使える鍵。主鍵)。 */
    internal fun ownSigningKey(): PGPPrivateKey = privateKey(own.publicKey.keyID)!!

    /** 自社の [keyId] の秘密鍵(復号に使う)。自社の鍵でなければ null。 */
    internal fun privateKey(keyId: Long): PGPPrivateKey? =
        own.getSecretKey(keyId)?.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder(JcaPGPDigestCalculatorProviderBuilder().setProvider(PROVIDER).build())
                .setProvider(PROVIDER)
                .build(passphrase),
        )

    override fun toString(): String = "PgpKeys(partner=$partnerFingerprint, own=$ownFingerprint)"

    public companion object {
        internal val PROVIDER: Provider = BouncyCastleProvider()

        /** [SecretProvider] から読む。値は例外のメッセージにも入れない。 */
        public fun load(
            secrets: SecretProvider,
            names: PgpKeyNames,
        ): Result<PgpKeys, FileTransferError> {
            val values =
                listOf(names.partnerPublicKey, names.ownSecretKey, names.ownPassphrase).map { name ->
                    when (val got = secrets.get(name)) {
                        is Result.Err -> {
                            return err(
                                if (got.error is DomainError.Retryable) {
                                    CredentialUnavailable(got.error.code)
                                } else {
                                    CredentialRejected("$name: ${got.error.code}")
                                },
                            )
                        }

                        is Result.Ok -> {
                            got.value
                        }
                    }
                }
            return parse(values[0], values[1], values[2])
        }

        /** ASCII armor の鍵から作る。形式・用途の鍵・パスフレーズの誤りは [CredentialRejected](値は入れない)。 */
        @Suppress("ReturnCount") // 公開鍵・秘密鍵・用途・パスフレーズのそれぞれの誤りで返す
        public fun parse(
            partnerPublicKey: Secret,
            ownSecretKey: Secret,
            ownPassphrase: Secret,
        ): Result<PgpKeys, FileTransferError> {
            val partner =
                try {
                    PGPPublicKeyRing(PGPUtil.getDecoderStream(partnerPublicKey.reveal().byteInputStream()), JcaKeyFingerprintCalculator())
                } catch (_: IOException) {
                    return err(CredentialRejected("取引先の公開鍵を読めません"))
                }
            val own =
                try {
                    PGPSecretKeyRing(PGPUtil.getDecoderStream(ownSecretKey.reveal().byteInputStream()), JcaKeyFingerprintCalculator())
                } catch (_: IOException) {
                    return err(CredentialRejected("自社の秘密鍵を読めません"))
                } catch (_: PGPException) {
                    return err(CredentialRejected("自社の秘密鍵を読めません"))
                }
            if (encryptionKey(partner.publicKeys.asSequence()) == null) return err(CredentialRejected("取引先の公開鍵に暗号化の鍵がありません"))
            if (!own.publicKey.isMasterKey || own.secretKey.isPrivateKeyEmpty) return err(CredentialRejected("自社の秘密鍵に署名の鍵がありません"))
            if (own.secretKey.keyEncryptionAlgorithm == 0) return err(CredentialRejected("自社の秘密鍵がパスフレーズで保護されていません"))
            val keys = PgpKeys(partner, own, ownPassphrase.reveal().toCharArray())
            return try {
                keys.ownSigningKey()
                ok(keys)
            } catch (_: PGPException) {
                err(CredentialRejected("自社の秘密鍵のパスフレーズが違います"))
            }
        }

        /**
         * 取引先の公開鍵の中に書かれた有効期限(主鍵と、暗号化に使う鍵のうち早いほう)。期限のない鍵・読めない鍵は null
         * (読めない鍵は送信のときに [CredentialRejected] になる)。鍵の期限の監視に使う(ADR-0040 §3)。
         */
        public fun publicKeyExpiry(partnerPublicKey: Secret): Instant? {
            val ring =
                try {
                    PGPPublicKeyRing(PGPUtil.getDecoderStream(partnerPublicKey.reveal().byteInputStream()), JcaKeyFingerprintCalculator())
                } catch (_: IOException) {
                    return null
                }
            return listOfNotNull(ring.publicKey, encryptionKey(ring.publicKeys.asSequence()))
                .filter { it.validSeconds > 0 }
                .minOfOrNull { it.creationTime.toInstant().plusSeconds(it.validSeconds) }
        }

        private fun encryptionKey(keys: Sequence<PGPPublicKey>): PGPPublicKey? =
            keys.filter { it.isEncryptionKey && !it.hasRevocation() }.sortedBy { it.isMasterKey }.firstOrNull()

        private fun fingerprint(key: PGPPublicKey): String = HexFormat.of().withUpperCase().formatHex(key.fingerprint)
    }
}

/** [PgpKeys] を読む秘密情報の名前。 */
public data class PgpKeyNames(
    val partnerPublicKey: SecretName,
    val ownSecretKey: SecretName,
    val ownPassphrase: SecretName,
)
