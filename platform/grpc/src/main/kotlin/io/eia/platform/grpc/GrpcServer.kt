package io.eia.platform.grpc

import io.eia.platform.security.jwt.JwtVerificationError
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.tls.ServerTls
import io.eia.shared.kernel.Result
import io.eia.shared.sdk.http.token.ScopeToken
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.TlsServerCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 公開するサービスと、呼び出しに必要なスコープ。スコープのないサービスは公開できない(ゼロトラスト。ADR-0042 §5)。 */
public class GrpcService(
    public val service: BindableService,
    public val requiredScopes: Set<String>,
) {
    init {
        require(requiredScopes.isNotEmpty()) { "gRPC のサービスにはスコープが必要です" }
        requiredScopes.forEach(ScopeToken::require)
    }
}

/**
 * @property port mTLS で受けるポート。0 は空いているポート(テスト)
 * @property allowedClients クライアント証明書の SAN の DNS 名の許可の一覧。空は認めない
 * @property verify トークンの検証(`JwtVerifier::verify`)
 * @property maxBudget 1 回の呼び出しの上限。呼び出し元の deadline がない・これより長いときに使う
 */
@Suppress("LongParameterList") // サーバの設定の項目。呼び出しは名前つきの引数で書く
public class GrpcServerConfig(
    public val port: Int,
    public val tls: ServerTls,
    public val allowedClients: Set<String>,
    public val verify: suspend (String) -> Result<VerifiedToken, JwtVerificationError>,
    public val telemetry: GrpcTelemetry,
    public val services: List<GrpcService>,
    public val maxBudget: Duration = 3.seconds,
) {
    init {
        require(allowedClients.isNotEmpty()) { "クライアント証明書の許可の一覧が空です" }
        require(services.isNotEmpty()) { "公開する gRPC のサービスがありません" }
        require(maxBudget.isPositive()) { "maxBudget は正の値です: $maxBudget" }
    }
}

/**
 * mTLS だけで受ける gRPC のサーバ(ADR-0042 §5)。サービスはサーバをここでだけ作る(Konsist の `grpcBuildersOnlyInPlatform`)。
 *
 * - クライアント証明書は必須(`REQUIRE`)。平文の接続とクライアント証明書のない接続は、ハンドシェイクで失敗する。
 * - すべての呼び出しに、許可の一覧・JWT・スコープの確認と、可観測性・締め切りを適用する([GrpcServerInterceptor])。
 * - **リフレクションは持たない**(本番で誤って有効にしないよう、実装しない。Konsist の `grpcReflectionForbidden`)。
 */
public class GrpcServer private constructor(
    private val server: Server,
) {
    public val port: Int get() = server.port

    /** 新しい呼び出しを断り、[grace] まで処理中の呼び出しを待ってから止める。 */
    public fun stop(grace: Duration = DEFAULT_GRACE) {
        server.shutdown()
        if (!server.awaitTermination(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) server.shutdownNow()
    }

    public companion object {
        private const val MAX_INBOUND_BYTES = 1024 * 1024
        private const val MAX_CALLS_PER_CONNECTION = 100
        private const val HANDSHAKE_TIMEOUT_SECONDS = 5L

        // クライアント(GrpcChannels)の keepalive の間隔より短くする(長いと、相手に切られる)
        private const val PERMIT_KEEP_ALIVE_SECONDS = 20L
        private val DEFAULT_GRACE = 5.seconds

        @Suppress("SpreadOperator") // 起動時に 1 回だけ
        public fun start(config: GrpcServerConfig): GrpcServer {
            val metrics = GrpcMetrics(config.telemetry.meter)
            val credentials =
                TlsServerCredentials
                    .newBuilder()
                    .keyManager(*config.tls.keyManagers())
                    .trustManager(
                        *config.tls
                            .trustManagers()
                            .map { counting(it, metrics) }
                            .toTypedArray(),
                    ).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build()
            val builder =
                NettyServerBuilder
                    .forPort(config.port, credentials)
                    .handshakeTimeout(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .maxInboundMessageSize(MAX_INBOUND_BYTES)
                    .maxConcurrentCallsPerConnection(MAX_CALLS_PER_CONNECTION)
                    .permitKeepAliveTime(PERMIT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
            config.services.forEach { service ->
                val main = GrpcServerInterceptor(config, service.requiredScopes, metrics)
                builder.addService(ServerInterceptors.intercept(service.service, CallCoroutineContext, main))
            }
            return GrpcServer(builder.build().start())
        }

        private fun counting(
            manager: TrustManager,
            metrics: GrpcMetrics,
        ): TrustManager = if (manager is X509TrustManager) CountingTrustManager(manager, metrics) else manager
    }
}
