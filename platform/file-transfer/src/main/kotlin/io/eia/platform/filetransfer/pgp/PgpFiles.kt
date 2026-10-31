package io.eia.platform.filetransfer.pgp

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.LocalFileFailed
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Date
import kotlin.io.path.deleteIfExists

/**
 * ファイルの PGP の暗号化と署名、復号と署名の検証(OpenPGP。RFC 4880。Framework 9.2・12.2。ADR-0032 §8)。
 *
 * 送る: 取引先の公開鍵で暗号化し、自社の秘密鍵で署名する(`gpg --sign --encrypt` と同じ形: 暗号化 → 圧縮 → 一括署名 + 本文 + 署名)。
 * - 暗号は AES-256、改竄の検知の付いた形(MDC。SEIPD)。圧縮は ZLIB。署名のハッシュは SHA-512。出力は ASCII armor にしない
 *
 * 受け取る: 自社の秘密鍵で復号し、取引先の公開鍵で署名を確かめる。**署名と改竄の検知の両方が確かめられるまで、本文を合格にしない**
 * (確かめられなければ、書き出した本文を消して [PgpVerification.Rejected] を返す)。
 */
public object PgpFiles {
    private const val BUFFER_BYTES = 1 shl 16

    /** [source] を暗号化・署名して [target] に書く。[literalName] は暗号文の中のファイル名(元の名前)。 */
    public fun encryptAndSign(
        source: Path,
        target: Path,
        keys: PgpKeys,
        literalName: String,
    ): Result<Unit, FileTransferError> =
        try {
            Files.newOutputStream(target).buffered().use { out -> writeEncrypted(source, out, keys, literalName) }
            ok(Unit)
        } catch (e: IOException) {
            err(LocalFileFailed(e::class.simpleName ?: "IOException"))
        } catch (_: PGPException) {
            err(LocalFileFailed("PGP の暗号化に失敗しました"))
        }

    /** 暗号化(AES-256 + MDC) → 圧縮(ZLIB) → 署名つきの本文。 */
    private fun writeEncrypted(
        source: Path,
        out: OutputStream,
        keys: PgpKeys,
        literalName: String,
    ) {
        val encryptor =
            PGPEncryptedDataGenerator(
                JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(SecureRandom())
                    .setProvider(PgpKeys.PROVIDER),
            )
        encryptor.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(keys.partnerEncryptionKey).setProvider(PgpKeys.PROVIDER))
        val encryptedOut = encryptor.open(out, ByteArray(BUFFER_BYTES))
        val compressedOut = PGPCompressedDataGenerator(CompressionAlgorithmTags.ZLIB).open(encryptedOut)
        sign(source, compressedOut, keys, literalName)
        compressedOut.close()
        encryptedOut.close()
    }

    private fun sign(
        source: Path,
        out: OutputStream,
        keys: PgpKeys,
        literalName: String,
    ) {
        val signer =
            PGPSignatureGenerator(
                JcaPGPContentSignerBuilder(keys.own.publicKey.algorithm, HashAlgorithmTags.SHA512).setProvider(PgpKeys.PROVIDER),
                keys.own.publicKey,
            )
        signer.init(PGPSignature.BINARY_DOCUMENT, keys.ownSigningKey())
        signer.generateOnePassVersion(false).encode(out)
        val literalOut = PGPLiteralDataGenerator().open(out, PGPLiteralData.BINARY, literalName, Date(), ByteArray(BUFFER_BYTES))
        Files.newInputStream(source).use { input -> copy(input, literalOut) { buffer, read -> signer.update(buffer, 0, read) } }
        literalOut.close()
        signer.generate().encode(out)
    }

    /** [input] を [out] に写しながら、写した分を [observe] に渡す(署名の計算・検証)。 */
    private inline fun copy(
        input: InputStream,
        out: OutputStream,
        observe: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            observe(buffer, read)
        }
    }

    /**
     * [source] を復号して [target] に書き、取引先の署名と改竄の検知を確かめる。
     * 不合格([PgpVerification.Rejected])なら [target] は消す。一時ファイルを読み書きできなければ [LocalFileFailed]。
     */
    public fun decryptAndVerify(
        source: Path,
        target: Path,
        keys: PgpKeys,
    ): Result<PgpVerification, FileTransferError> {
        val result =
            try {
                Files.newInputStream(source).buffered().use { input -> Decryption(keys, target).run(input) }
            } catch (_: PGPException) {
                PgpVerification.Rejected(PgpRejection.MALFORMED)
            } catch (_: IOException) {
                // 暗号文の形の誤り(BC は壊れた入力を IOException で知らせる)。ローカルの失敗と区別できないので、不合格として扱い、本文は消す
                PgpVerification.Rejected(PgpRejection.MALFORMED)
            } catch (_: IllegalStateException) {
                PgpVerification.Rejected(PgpRejection.MALFORMED)
            }
        if (result is PgpVerification.Rejected) {
            try {
                target.deleteIfExists()
            } catch (e: IOException) {
                return err(LocalFileFailed(e::class.simpleName ?: "IOException"))
            }
        }
        return ok(result)
    }

    /** 復号の 1 回分。パケットの並びは、暗号化 → (圧縮) → 一括署名 → 本文 → 署名 だけを受け付ける。 */
    private class Decryption(
        private val keys: PgpKeys,
        private val target: Path,
    ) {
        @Suppress("ReturnCount") // パケットの並びの誤り・鍵の誤りのそれぞれで不合格を返す
        fun run(input: InputStream): PgpVerification {
            val encryptedList =
                JcaPGPObjectFactory(PGPUtil.getDecoderStream(input)).firstOf<PGPEncryptedDataList>()
                    ?: return PgpVerification.Rejected(PgpRejection.NOT_ENCRYPTED)
            val encrypted =
                encryptedList.encryptedDataObjects
                    .asSequence()
                    .filterIsInstance<PGPPublicKeyEncryptedData>()
                    .firstOrNull { keys.own.getSecretKey(it.keyID) != null }
                    ?: return PgpVerification.Rejected(PgpRejection.NOT_FOR_US)
            if (!encrypted.isIntegrityProtected) return PgpVerification.Rejected(PgpRejection.NO_INTEGRITY)
            val privateKey = keys.privateKey(encrypted.keyID) ?: return PgpVerification.Rejected(PgpRejection.NOT_FOR_US)
            val clear = encrypted.getDataStream(JcePublicKeyDataDecryptorFactoryBuilder().setProvider(PgpKeys.PROVIDER).build(privateKey))
            return verifySigned(encrypted, decompressed(JcaPGPObjectFactory(clear)))
        }

        /** 圧縮されていれば、その中のパケットの並びと、先頭のパケット。 */
        private fun decompressed(factory: JcaPGPObjectFactory): Pair<JcaPGPObjectFactory, Any?> {
            val first = factory.nextObject()
            if (first !is PGPCompressedData) return factory to first
            val inner = JcaPGPObjectFactory(first.dataStream)
            return inner to inner.nextObject()
        }

        /** 一括署名 → 本文 → 署名 の並びを読み、本文を [target] に書いて、署名と改竄の検知を確かめる。 */
        @Suppress("ReturnCount") // 署名がない・知らない署名者・本文がないで返す
        private fun verifySigned(
            encrypted: PGPPublicKeyEncryptedData,
            packets: Pair<JcaPGPObjectFactory, Any?>,
        ): PgpVerification {
            val (factory, first) = packets
            val onePass =
                (first as? PGPOnePassSignatureList)?.takeIf { it.size() == 1 }?.get(0)
                    ?: return PgpVerification.Rejected(PgpRejection.UNSIGNED)
            val (signerKey, signerFingerprint) = keys.signer(onePass.keyID) ?: return PgpVerification.Rejected(PgpRejection.UNKNOWN_SIGNER)
            onePass.init(JcaPGPContentVerifierBuilderProvider().setProvider(PgpKeys.PROVIDER), signerKey)
            val literal = factory.nextObject() as? PGPLiteralData ?: return PgpVerification.Rejected(PgpRejection.MALFORMED)
            Files.newOutputStream(target).buffered().use { out ->
                literal.inputStream.use { body -> copy(body, out) { buffer, read -> onePass.update(buffer, 0, read) } }
            }
            val signature = (factory.nextObject() as? PGPSignatureList)?.takeIf { it.size() == 1 }?.get(0)
            return when {
                signature == null || signature.keyID != onePass.keyID -> PgpVerification.Rejected(PgpRejection.UNSIGNED)
                !onePass.verify(signature) -> PgpVerification.Rejected(PgpRejection.BAD_SIGNATURE)
                !encrypted.verify() -> PgpVerification.Rejected(PgpRejection.INTEGRITY_FAILED)
                else -> PgpVerification.Verified(signerFingerprint, literal.fileName)
            }
        }

        /** 先頭のマーカーなどを飛ばして、最初の [T] を返す。 */
        private inline fun <reified T> JcaPGPObjectFactory.firstOf(): T? {
            while (true) {
                val next = nextObject() ?: return null
                if (next is T) return next
            }
        }
    }
}

/** 復号と署名の検証の結果。 */
public sealed interface PgpVerification {
    /**
     * 取引先の鍵の署名が正しく、改竄の検知も通った。[signerFingerprint] は署名した鍵(今の鍵か、差し替えの間の旧い鍵)の主鍵のフィンガープリント。
     *
     * @property literalName 暗号文の中のファイル名(送り手が付けた元の名前。信用せず、参考にだけ使う)
     */
    public data class Verified(
        val signerFingerprint: String,
        val literalName: String,
    ) : PgpVerification

    public data class Rejected(
        val reason: PgpRejection,
    ) : PgpVerification
}

/** 不合格の理由。 */
public enum class PgpRejection(
    public val code: String,
) {
    /** PGP の暗号文ではない */
    NOT_ENCRYPTED("pgp_not_encrypted"),

    /** 自社の鍵あてに暗号化されていない */
    NOT_FOR_US("pgp_not_for_us"),

    /** 改竄の検知(MDC)のない古い形式 */
    NO_INTEGRITY("pgp_no_integrity"),

    /** 署名がない */
    UNSIGNED("pgp_unsigned"),

    /** 取引先の鍵ではない鍵で署名されている */
    UNKNOWN_SIGNER("pgp_unknown_signer"),

    /** 署名が本文と一致しない(改竄) */
    BAD_SIGNATURE("pgp_bad_signature"),

    /** 改竄の検知(MDC)が一致しない */
    INTEGRITY_FAILED("pgp_integrity_failed"),

    /** パケットの並び・形式が誤っている */
    MALFORMED("pgp_malformed"),
}
