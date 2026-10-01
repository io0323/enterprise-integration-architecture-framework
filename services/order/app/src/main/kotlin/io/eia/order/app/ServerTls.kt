package io.eia.order.app

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * API のポートの mTLS の鍵と証明書(ADR-0024 §6)。PEM のファイルからメモリ上の [KeyStore] を作る(キーストアのパスワードという
 * 秘密情報を増やさないため。パスワードはプロセスの中で乱数から作る)。
 *
 * 起動時に有効期限を確かめる。サーバ証明書か CA が期限切れ(またはまだ有効でない)なら起動しない。原因と直し方
 * (`make certs`。docs/runbooks/dev-certificates.md)をエラーに書く。残りが [EXPIRY_WARNING] を切っていれば WARN を残す。
 */
internal class ServerTls private constructor(
    val keyStore: KeyStore,
    val trustStore: KeyStore,
    private val password: CharArray,
) {
    /** Ktor の `sslConnector` に渡すパスワード(Ktor は使った後に配列を消すので、毎回複製を返す)。 */
    fun password(): CharArray = password.copyOf()

    companion object {
        const val KEY_ALIAS = "order-service"

        /** 残りがこれを切ったら WARN を残す(`make up` / `make certs` が作り直す境界と同じ。gen-dev-certs.sh)。 */
        val EXPIRY_WARNING: Duration = 7.days

        private const val PASSWORD_BYTES = 24
        private const val RENEW_HINT = "make certs で作り直してください(docs/runbooks/dev-certificates.md)"
        private val logger = LoggerFactory.getLogger(ServerTls::class.java)

        fun load(
            files: TlsFiles,
            now: Instant = Clock.System.now(),
        ): Result<ServerTls, ValidationError> {
            val missing =
                listOf(TlsFiles.CERT to files.cert, TlsFiles.KEY to files.key, TlsFiles.CLIENT_CA to files.clientCa)
                    .filter { it.second == null }
                    .map { FieldViolation(it.first, "serve には必須です") }
            if (missing.isNotEmpty()) return err(ValidationError(missing))
            return try {
                build(Path.of(files.cert!!), Path.of(files.key!!), Path.of(files.clientCa!!), now)
            } catch (e: IOException) {
                err(ValidationError.of("ORDER_TLS_*", "証明書か鍵のファイルを読めません(${e::class.simpleName})。$RENEW_HINT"))
            } catch (e: GeneralSecurityException) {
                err(ValidationError.of("ORDER_TLS_*", "証明書か鍵の形式が不正です(${e::class.simpleName})。$RENEW_HINT"))
            }
        }

        private fun build(
            certFile: Path,
            keyFile: Path,
            caFile: Path,
            now: Instant,
        ): Result<ServerTls, ValidationError> {
            val chain = certificates(certFile)
            val cas = certificates(caFile)
            val violations =
                validity(TlsFiles.CERT, chain.firstOrNull(), now) + cas.flatMap { validity(TlsFiles.CLIENT_CA, it, now) }
            if (violations.isNotEmpty()) return err(ValidationError(violations))

            val password = randomPassword()
            val keyStore =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry(KEY_ALIAS, privateKey(keyFile), password, chain.toTypedArray())
                }
            val trustStore =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    cas.forEachIndexed { i, ca -> setCertificateEntry("client-ca-$i", ca) }
                }
            return ok(ServerTls(keyStore, trustStore, password))
        }

        /** 期限切れ・まだ有効でない証明書は違反にする。残りが短ければ WARN を残す。 */
        private fun validity(
            field: String,
            cert: X509Certificate?,
            now: Instant,
        ): List<FieldViolation> {
            if (cert == null) return listOf(FieldViolation(field, "証明書がありません。$RENEW_HINT"))
            val notBefore = cert.notBefore.toInstant().toKotlinInstant()
            val notAfter = cert.notAfter.toInstant().toKotlinInstant()
            return when {
                now >= notAfter -> {
                    listOf(FieldViolation(field, "証明書の有効期限が切れています(notAfter=$notAfter)。$RENEW_HINT"))
                }

                now < notBefore -> {
                    listOf(FieldViolation(field, "証明書がまだ有効ではありません(notBefore=$notBefore)。時計か証明書を確かめてください"))
                }

                else -> {
                    if (notAfter - now < EXPIRY_WARNING) {
                        logger.warn("{} の証明書の有効期限が近づいています(notAfter={})。{}", field, notAfter, RENEW_HINT)
                    }
                    emptyList()
                }
            }
        }

        private fun certificates(file: Path): List<X509Certificate> =
            Files.newInputStream(file).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
            }

        /** PKCS#8 の PEM(`BEGIN PRIVATE KEY`)。EC と RSA を受け付ける。 */
        private fun privateKey(file: Path): PrivateKey {
            val pem = Files.readString(file)
            val body =
                PKCS8_PEM.find(pem)?.groupValues?.get(1)
                    ?: throw GeneralSecurityException("PKCS#8 の PEM(BEGIN PRIVATE KEY)ではありません")
            val spec = PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body))
            return KEY_ALGORITHMS.firstNotNullOfOrNull { algorithm ->
                try {
                    KeyFactory.getInstance(algorithm).generatePrivate(spec)
                } catch (_: GeneralSecurityException) {
                    null
                }
            } ?: throw GeneralSecurityException("EC か RSA の秘密鍵ではありません")
        }

        private fun randomPassword(): CharArray =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes))
                .toCharArray()

        private val PKCS8_PEM = Regex("-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----")
        private val KEY_ALGORITHMS = listOf("EC", "RSA")
    }
}
