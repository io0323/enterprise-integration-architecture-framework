package io.eia.platform.grpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ConnectException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** 例外の連鎖の判別(実際の TLS の失敗は GrpcTlsFailureSpec で確かめる)。 */
class CertificateFailuresSpec :
    FunSpec({
        fun handshake(cause: Throwable) = SSLHandshakeException("General OpenSslEngine problem").apply { initCause(cause) }

        test("証明書の原因が連鎖の奥にあっても判別する") {
            CertificateFailures.classify(handshake(CertificateException("x", CertificateExpiredException("y")))) shouldBe
                CertificateFailureReason.EXPIRED
            CertificateFailures.classify(handshake(CertificateException("No subject alternative DNS name matching a found"))) shouldBe
                CertificateFailureReason.HOSTNAME_MISMATCH
            CertificateFailures.classify(handshake(CertificateException("PKIX path building failed"))) shouldBe
                CertificateFailureReason.UNTRUSTED
        }

        test("相手に拒否された alert を、BoringSSL の名前と JDK の文の両方で判別する") {
            CertificateFailures.classify(
                SSLException("error:1000045c:SSL routines:OPENSSL_internal:TLSV1_ALERT_CERTIFICATE_REQUIRED"),
            ) shouldBe
                CertificateFailureReason.REJECTED_BY_PEER
            CertificateFailures.classify(
                SSLException("error:10000415:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_EXPIRED"),
            ) shouldBe
                CertificateFailureReason.EXPIRED
            CertificateFailures.classify(SSLException("Received fatal alert: bad_certificate")) shouldBe
                CertificateFailureReason.REJECTED_BY_PEER
            CertificateFailures.classify(SSLException("Received fatal alert: certificate_expired")) shouldBe
                CertificateFailureReason.EXPIRED
        }

        test("証明書と関係のない失敗は判別しない(一時的な失敗として扱う)") {
            CertificateFailures.classify(null) shouldBe null
            CertificateFailures.classify(ConnectException("Connection refused")) shouldBe null
            CertificateFailures.classify(SSLHandshakeException("Remote host terminated the handshake")) shouldBe null
            CertificateFailures.classify(SSLException("Received fatal alert: handshake_failure")) shouldBe null
        }
    })
