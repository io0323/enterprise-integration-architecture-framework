package io.eia.platform.security.tls

import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.NettyApplicationEngine

/** Framework 12.2: TLS 1.2 以上。 */
private val TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

/**
 * API のポート(mTLS)とヘルスチェックのポート(平文)の 2 つのコネクタ(ADR-0024 §6)。
 *
 * - API のポート([httpsPort]): クライアント証明書は必須で、[tls] の CA の署名と、SAN の許可の一覧([allowedClients])を確かめる。
 *   ルートは [overTls] の下に置く。
 * - ヘルスチェックのポート([healthPort]): 平文で、`/health/live` と `/health/ready` だけを置く([overPlaintext])。コンテナの外には公開しない。
 */
public fun NettyApplicationEngine.Configuration.mutualTlsConnectors(
    httpsPort: Int,
    healthPort: Int,
    tls: ServerTls,
    allowedClients: Set<String>,
) {
    require(allowedClients.isNotEmpty()) { "許可の一覧が空です" }
    connector { port = healthPort }
    sslConnector(tls.keyStore, ServerTls.KEY_ALIAS, tls::password, tls::password) {
        port = httpsPort
        // trustStore を設定すると、Ktor はクライアント証明書を必須にする(needClientAuth)
        trustStore = tls.trustStore
        enabledProtocols = TLS_PROTOCOLS
    }
    // 許可の一覧の確認は HTTP/1.1 のパイプラインに置く(ゲートウェイとの間は HTTP/1.1)
    enableHttp2 = false
    channelPipelineConfig = {
        if (get("ssl") != null) addAfter("ssl", ClientCertificateAllowList.NAME, ClientCertificateAllowList(allowedClients))
    }
}
