package io.eia.platform.grpc

import io.eia.platform.security.tls.ServerTls
import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.TlsChannelCredentials
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

/**
 * mTLS の gRPC のチャネルを作る(ADR-0042 §5)。サービスはチャネルをここでだけ作る(Konsist の `grpcBuildersOnlyInPlatform`)。
 *
 * - **自動のリトライは止める**(`disableRetry`)。再試行は `Resilience` だけが行う(重ねると回数が掛け算になる)。
 * - [target] の host を、サーバ証明書の SAN と照合する。
 */
public object GrpcChannels {
    private const val KEEP_ALIVE_SECONDS = 30L
    private const val KEEP_ALIVE_TIMEOUT_SECONDS = 10L

    @Suppress("SpreadOperator") // チャネルを作るときに 1 回だけ
    public fun mutualTls(
        target: String,
        tls: ServerTls,
    ): ManagedChannel {
        val credentials =
            TlsChannelCredentials
                .newBuilder()
                .keyManager(*tls.keyManagers())
                .trustManager(*tls.trustManagers())
                .build()
        return Grpc
            .newChannelBuilder(target, credentials)
            .disableRetry()
            .keepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
            .keepAliveTimeout(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

internal fun ServerTls.keyManagers() =
    KeyManagerFactory
        .getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, password()) }
        .keyManagers

internal fun ServerTls.trustManagers() =
    TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(trustStore) }
        .trustManagers
