package io.eia.order.app

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * mTLS のクライアントの許可の一覧(ADR-0024 §6)。Netty のパイプラインで、TLS の処理(`ssl`)の直後に置く。
 *
 * 証明書の署名の検証(開発用 CA で署名されているか・期限)は Ktor の `sslConnector` の trustStore が行う(クライアント証明書は必須)。
 * ここでは、さらに、クライアント証明書の SAN の DNS 名が [allowed] のどれかであることを確かめる。同じ CA が ほかのサービスの
 * 証明書も署名するため、CA の検証だけでは、ゲートウェイ以外のクライアントも API のポートに接続できてしまう。
 *
 * 許可されていなければ、HTTP の処理に渡す前に接続を閉じる。ハンドシェイクが終わる前に届いたデータも渡さない。
 */
internal class ClientCertificateAllowList(
    private val allowed: Set<String>,
) : ChannelInboundHandlerAdapter() {
    private var verified = false

    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any,
    ) {
        if (evt is SslHandshakeCompletionEvent && evt.isSuccess) {
            verified = isAllowed(ctx)
            if (!verified) {
                // 証明書の内容(個人や組織の名前を含みうる)はログに出さない
                logger.warn("許可の一覧にないクライアント証明書の接続を閉じました")
                ctx.close()
                return
            }
        }
        ctx.fireUserEventTriggered(evt)
    }

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        if (!verified) {
            ReferenceCountUtil.release(msg)
            ctx.close()
            return
        }
        ctx.fireChannelRead(msg)
    }

    private fun isAllowed(ctx: ChannelHandlerContext): Boolean {
        val session =
            ctx
                .pipeline()
                .get(SslHandler::class.java)
                ?.engine()
                ?.session ?: return false
        val peer =
            try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: SSLPeerUnverifiedException) {
                null
            }
        return peer != null && dnsNames(peer).any { it in allowed }
    }

    companion object {
        const val NAME = "eia-client-allow-list"
        private const val DNS_NAME = 2
        private val logger = LoggerFactory.getLogger(ClientCertificateAllowList::class.java)

        /** SAN の DNS 名(RFC 5280 §4.2.1.6 の dNSName)。 */
        fun dnsNames(cert: X509Certificate): List<String> =
            cert.subjectAlternativeNames
                .orEmpty()
                .filter { it.size >= 2 && it[0] == DNS_NAME }
                .mapNotNull { it[1] as? String }
    }
}
