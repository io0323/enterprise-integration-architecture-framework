@file:Suppress("MagicNumber") // 鍵の長さ・有効期間

package io.eia.order.app

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * テスト用の CA と証明書(infra/local/scripts/gen-dev-certs.sh と同じ形: EC P-256、PKCS#8 の鍵、SAN の DNS 名)。
 * ファイルは一時ディレクトリに書き、終わったら消す。
 */
internal class TestPki(
    name: String = "EIAF Test CA",
    caValidFrom: Instant = Clock.System.now() - 1.days,
) : AutoCloseable {
    private val dir: Path = Files.createTempDirectory("eiaf-pki")
    private val caKey: KeyPair = keyPair()
    val ca: X509Certificate =
        sign(X500Name("CN=$name"), caKey.public, caKey, X500Name("CN=$name"), isCa = true, validFrom = caValidFrom)

    /** CA の PEM。 */
    val caFile: Path = write("ca.crt") { it.writeObject(ca) }

    /** この CA で署名した証明書と鍵(PEM のファイル)。 */
    inner class Issued(
        val key: KeyPair,
        val cert: X509Certificate,
        val certFile: Path,
        val keyFile: Path,
    ) {
        /** この証明書をクライアント証明書として出し、[trusted] の CA でサーバを検証する [SSLContext]。 */
        fun clientContext(trusted: TestPki): SSLContext {
            val password = "test".toCharArray()
            val keyStore =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry("client", key.private, password, arrayOf(cert, ca))
                }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
            return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, trusted.trustManagers(), SecureRandom()) }
        }
    }

    /** SAN に [dnsNames] を持つ証明書(サーバにもクライアントにも使える)。 */
    fun issue(
        commonName: String,
        dnsNames: List<String>,
        validFrom: Instant = Clock.System.now() - 1.days,
        validity: Duration = 30.days,
    ): Issued {
        val key = keyPair()
        val cert =
            sign(
                X500Name("CN=$commonName"),
                key.public,
                caKey,
                X500Name.getInstance(ca.subjectX500Principal.encoded),
                isCa = false,
                dnsNames = dnsNames,
                validFrom = validFrom,
                validity = validity,
            )
        val certFile = write("$commonName-${cert.serialNumber}.crt") { it.writeObject(cert) }
        val keyFile = write("$commonName-${cert.serialNumber}.key") { it.writeObject(JcaPKCS8Generator(key.private, null)) }
        return Issued(key, cert, certFile, keyFile)
    }

    /** CA だけを信頼し、クライアント証明書を出さない [SSLContext]。 */
    fun anonymousClientContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, trustManagers(), SecureRandom()) }

    private fun trustManagers() =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                init(
                    KeyStore.getInstance("PKCS12").apply {
                        load(null, null)
                        setCertificateEntry("ca", ca)
                    },
                )
            }.trustManagers

    private fun write(
        file: String,
        block: (JcaPEMWriter) -> Unit,
    ): Path {
        val path = dir.resolve(file)
        Files.newBufferedWriter(path).use { out -> JcaPEMWriter(out).use(block) }
        return path
    }

    override fun close() {
        dir.toFile().deleteRecursively()
    }

    private companion object {
        val random = SecureRandom()

        fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        @Suppress("LongParameterList")
        fun sign(
            subject: X500Name,
            publicKey: PublicKey,
            issuerKey: KeyPair,
            issuer: X500Name,
            isCa: Boolean,
            dnsNames: List<String> = emptyList(),
            validFrom: Instant = Clock.System.now() - 1.days,
            validity: Duration = 30.days,
        ): X509Certificate {
            val notBefore = Date(validFrom.toEpochMilliseconds())
            val notAfter = Date((validFrom + validity).toEpochMilliseconds())
            val builder =
                JcaX509v3CertificateBuilder(issuer, BigInteger(64, random), notBefore, notAfter, subject, publicKey)
                    .addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            if (isCa) {
                builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            } else {
                builder
                    .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                    .addExtension(
                        Extension.extendedKeyUsage,
                        false,
                        ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
                    ).addExtension(
                        Extension.subjectAlternativeName,
                        false,
                        GeneralNames(dnsNames.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()),
                    )
            }
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}
