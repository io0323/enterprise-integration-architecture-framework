package io.eia.platform.grpc

import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * クライアント証明書を検証し、拒否した件数を理由ごとに数える(ADR-0042 §8)。判定は [delegate] のまま変えない。
 * 証明書の中身(個人や組織の名前を含みうる)は、ログにもメトリクスにも出さない。
 */
internal class CountingTrustManager(
    private val delegate: X509TrustManager,
    private val metrics: GrpcMetrics,
) : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        try {
            delegate.checkClientTrusted(chain, authType)
        } catch (e: CertificateException) {
            metrics.recordCertificateRejection(reason(e))
            throw e
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        delegate.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun reason(error: CertificateException): String =
        if (generateSequence<Throwable>(error) { it.cause }.take(MAX_DEPTH).any(::isExpired)) {
            CertificateFailureReason.EXPIRED.label
        } else {
            CertificateFailureReason.UNTRUSTED.label
        }

    private fun isExpired(error: Throwable) = error is CertificateExpiredException || error is CertificateNotYetValidException

    private companion object {
        const val MAX_DEPTH = 16
    }
}
