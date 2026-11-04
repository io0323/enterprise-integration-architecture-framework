package io.eia.bffgraphql.app

import io.eia.bffgraphql.adapters.graphql.StorefrontGraphql
import io.eia.bffgraphql.adapters.graphql.storefrontGraphql
import io.eia.platform.api.deadline.installRequestDeadline
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.prefetchUntilLoaded
import io.eia.platform.security.ktor.eiaJwt
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.mutualTlsConnectors
import io.eia.platform.security.tls.overPlaintext
import io.eia.platform.security.tls.overTls
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.grpc.ManagedChannel
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/**
 * bff-graphql のプロセス(ADR-0043)。
 *
 * - GraphQL は mTLS のポートだけで受ける(Gateway だけが呼べる。ADR-0024 §6)。ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。
 * - `/health/ready` は JWKS を取得できたときだけ UP。下流の状態は含めない(一部のサービスが止まっていても、部分応答を返すため。ADR-0043 §6)。
 *
 * プラグインの順序: `ServerObservability` → リクエストの予算 → Problem Details → 認証(order-service と同じ)。
 */
internal class BffServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
) {
    private val stopped = CountDownLatch(1)

    fun port(index: Int): Int = runBlocking { server.engine.resolvedConnectors()[index].port }

    fun awaitTermination() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        stopped.await()
    }

    fun stop() {
        if (stopped.count == 0L) return
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<ManagedChannel>(INVENTORY_CHANNEL).shutdown()
        koin.get<ManagedChannel>(SHIPPING_CHANNEL).shutdown()
        koin.get<HttpClient>().close()
        koin.get<JwtVerifier>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        /** `resolvedConnectors` の並び(`mutualTlsConnectors` の順)。 */
        const val HEALTH_CONNECTOR = 0
        const val HTTPS_CONNECTOR = 1
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private val logger = LoggerFactory.getLogger(BffServer::class.java)

        fun start(env: Map<String, String>): Result<BffServer, ValidationError> {
            val secrets = EnvSecretProvider(env)
            return BffConfig
                .fromEnvironment(env)
                .flatMap { config ->
                    // 起動してから最初の呼び出しで分かるのでは遅い
                    when (val secret = secrets.get(BffConfig.CLIENT_SECRET)) {
                        is Result.Ok -> ok(config)
                        is Result.Err -> err(ValidationError.of(BffConfig.CLIENT_SECRET.value, secret.error.message))
                    }
                }.flatMap { config -> ServerTls.load(config.tls).map { tls -> config to tls } }
                .flatMap { (config, tls) ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "bff-graphql") + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability ->
                        val runtime = Observability.init(observability)
                        val koin = koinApplication { modules(bffModule(config, tls, runtime, secrets)) }.koin
                        // 最初のリクエストで TLS の接続から始めると、下流の試行のタイムアウトに当たりやすい。起動時に接続を始める(ADR-0043 §6)
                        koin.get<ManagedChannel>(INVENTORY_CHANNEL).getState(true)
                        koin.get<ManagedChannel>(SHIPPING_CHANNEL).getState(true)
                        val server =
                            embeddedServer(Netty, configure = {
                                mutualTlsConnectors(config.httpsPort, config.healthPort, tls, config.allowedClients)
                            }) { bffApplication(koin, config) }
                                .start(wait = false)
                        logger.info("bff-graphql を起動しました")
                        BffServer(server, koin, runtime)
                    }
                }
        }

        private fun Application.bffApplication(
            koin: Koin,
            config: BffConfig,
        ) {
            install(ServerObservability) {
                runtime = koin.get()
                integrationId = "INT-STOREFRONT-001"
            }
            installRequestDeadline(config.requestBudget)
            installProblemDetails()
            install(Authentication) {
                eiaJwt {
                    verifier = koin.get()
                    realm = "eiaf"
                }
            }
            val verifier = koin.get<JwtVerifier>()
            routing {
                overPlaintext {
                    get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                    get("/health/ready") {
                        if (verifier.isReady) {
                            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
                        } else {
                            call.respondText("""{"status":"DOWN"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        }
                    }
                }
                overTls {
                    authenticate { storefrontGraphql(koin.get<StorefrontGraphql>()) }
                }
            }
            launch { verifier.prefetchUntilLoaded() }
        }
    }
}
