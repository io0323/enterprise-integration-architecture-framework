package io.eia.platform.grpc

import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException

/** 証明書のエラーの理由。[label] をメトリクスの属性に使う。 */
public enum class CertificateFailureReason(
    public val label: String,
) {
    /** 相手か自分の証明書の期限が切れている(まだ有効でない場合を含む)。 */
    EXPIRED("expired"),

    /** 相手の証明書を、信頼する CA で検証できない。 */
    UNTRUSTED("untrusted"),

    /** 相手の証明書の SAN に、接続先の名前がない。 */
    HOSTNAME_MISMATCH("hostname_mismatch"),

    /** 相手が自分のクライアント証明書を拒否した(証明書がない・相手の信頼する CA のものでない)。 */
    REJECTED_BY_PEER("rejected_by_peer"),
}

/**
 * TLS の失敗の原因が証明書かどうかを、例外の連鎖から判別する(ADR-0042 §8)。判別できなければ `null`(一時的な失敗として扱う)。
 *
 * - 自分が相手の証明書を検証した失敗は、`java.security.cert` の例外が連鎖に残る。
 * - 相手に拒否された失敗は TLS の alert で届き、BoringSSL のエラーの名前か JDK の文でしか分からない。文字列を見るので、
 *   grpc-java を上げて文が変わると判別できなくなる。その場合は `GrpcTlsFailureSpec` が失敗する。
 */
internal object CertificateFailures {
    private const val MAX_DEPTH = 16
    private val PEER_EXPIRED = Regex("""ALERT_CERTIFICATE_EXPIRED|fatal alert: certificate_expired""")
    private val PEER_REJECTED =
        Regex(
            """ALERT_(CERTIFICATE_(REQUIRED|REVOKED|UNKNOWN)|BAD_CERTIFICATE|UNKNOWN_CA|UNSUPPORTED_CERTIFICATE|ACCESS_DENIED)""" +
                """|fatal alert: (certificate_(required|revoked|unknown)|bad_certificate|unknown_ca""" +
                """|unsupported_certificate|access_denied)""",
        )
    private val HOSTNAME = Regex("""No subject alternative|No name matching""")

    fun classify(cause: Throwable?): CertificateFailureReason? {
        val chain = generateSequence(cause) { it.cause }.take(MAX_DEPTH).toList()
        return when {
            chain.any { it is CertificateExpiredException || it is CertificateNotYetValidException } -> {
                CertificateFailureReason.EXPIRED
            }

            chain.any { it is CertificateException && HOSTNAME.containsMatchIn(it.message.orEmpty()) } -> {
                CertificateFailureReason.HOSTNAME_MISMATCH
            }

            chain.any { it is CertificateException || it is CertPathBuilderException || it is CertPathValidatorException } -> {
                CertificateFailureReason.UNTRUSTED
            }

            chain.any { PEER_EXPIRED.containsMatchIn(it.message.orEmpty()) } -> {
                CertificateFailureReason.EXPIRED
            }

            chain.any { PEER_REJECTED.containsMatchIn(it.message.orEmpty()) } -> {
                CertificateFailureReason.REJECTED_BY_PEER
            }

            else -> {
                null
            }
        }
    }
}
