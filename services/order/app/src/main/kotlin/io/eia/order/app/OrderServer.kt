package io.eia.order.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.order.adapters.inbound.rest.OrderProblems
import io.eia.order.adapters.inbound.rest.orderRoutes
import io.eia.platform.api.deadline.installRequestDeadline
import io.eia.platform.api.idempotency.IdempotencyStore
import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.ktor.eiaJwt
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.CountDownLatch

/**
 * order-service の HTTP サーバ(serve。Ktor の Netty。ADR-0024 §1)。
 *
 * プラグインの順序: `ServerObservability`(Monitoring)→ リクエストの予算(`installRequestDeadline`)→ Problem Details → 認証 → ルート。
 *
 * コネクタは 2 つ(ADR-0024 §6):
 * - API のポート([OrderConfig.httpsPort]): mTLS だけで受ける。クライアント証明書は必須で、開発用 CA の署名と、SAN の許可の一覧
 *   ([ClientCertificateAllowList])を確かめる。API のルートはこのポートにだけある。
 * - ヘルスチェックのポート([OrderConfig.healthPort]): 平文で、`/health/live` と `/health/ready` だけを返す(認証しない)。
 *   コンテナのヘルスチェック用で、コンテナの外には公開しない。
 */
internal class OrderServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
) {
    private val stopped = CountDownLatch(1)

    /** API のポート(mTLS。`ORDER_HTTPS_PORT=0` のときは割り当てられたポート)。 */
    val httpsPort: Int get() = resolvedPort(ConnectorType.HTTPS)

    /** ヘルスチェックのポート(平文。`ORDER_HEALTH_PORT=0` のときは割り当てられたポート)。 */
    val healthPort: Int get() = resolvedPort(ConnectorType.HTTP)

    private fun resolvedPort(type: ConnectorType): Int =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first { it.type == type }
                .port
        }

    fun awaitTermination() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        stopped.await()
    }

    fun stop() {
        if (stopped.count == 0L) return
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<JwtVerifier>().close()
        koin.get<HikariDataSource>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val READY_TIMEOUT_SECONDS = 2

        /** Framework 12.2: TLS 1.2 以上。 */
        private val TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
        private val logger = LoggerFactory.getLogger(OrderServer::class.java)

        /**
         * 設定を読み、サーバを起動する。所有者のパスワード(`ORDER_DB_PASSWORD` / `ORDER_DB_PASSWORD_FILE`)が環境にあれば起動しない
         * (serve のプロセスは所有者の権限を持たない。ADR-0024 §2)。
         */
        fun start(env: Map<String, String>): Result<OrderServer, ValidationError> =
            serveInputs(env).flatMap { (config, appPassword) ->
                ServerTls.load(config.tls).flatMap { tls ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "order-service") + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability ->
                        val runtime = Observability.init(observability)
                        val koin = koinApplication { modules(orderModule(config, appPassword, runtime)) }.koin
                        val server =
                            embeddedServer(Netty, configure = { connectors(config, tls) }) { orderApplication(koin, config) }
                                .start(wait = false)
                        logger.info("order-service を起動しました")
                        OrderServer(server, koin, runtime)
                    }
                }
            }

        /** API のポート(mTLS)とヘルスチェックのポート(平文)。ADR-0024 §6。 */
        private fun NettyApplicationEngine.Configuration.connectors(
            config: OrderConfig,
            tls: ServerTls,
        ) {
            connector { port = config.healthPort }
            sslConnector(tls.keyStore, ServerTls.KEY_ALIAS, tls::password, tls::password) {
                port = config.httpsPort
                // trustStore を設定すると、Ktor はクライアント証明書を必須にする(needClientAuth)
                trustStore = tls.trustStore
                enabledProtocols = TLS_PROTOCOLS
            }
            // 許可の一覧の確認は HTTP/1.1 のパイプラインに置く(ゲートウェイとの間は HTTP/1.1)
            enableHttp2 = false
            channelPipelineConfig = {
                if (get("ssl") != null) addAfter("ssl", ClientCertificateAllowList.NAME, ClientCertificateAllowList(config.allowedClients))
            }
        }

        /** serve の設定とアプリのロールのパスワード。所有者のパスワードがあれば、設定の誤りにする。 */
        private fun serveInputs(env: Map<String, String>): Result<Pair<OrderConfig, Secret>, ValidationError> {
            val forbidden = OrderCommands.FORBIDDEN_FOR_SERVE.filter { env.containsKey(it) }
            val violations =
                forbidden.map { FieldViolation(it, "serve には所有者のパスワードを渡さないでください(migrate にだけ渡す)") } +
                    listOf("OIDC_ISSUER", "OIDC_JWKS_URI").filter { env[it].isNullOrBlank() }.map { FieldViolation(it, "serve には必須です") }
            return when {
                violations.isNotEmpty() -> err(ValidationError(violations))
                else -> OrderConfig.fromEnvironment(env).flatMap { config -> appPassword(env).map { config to it } }
            }
        }

        private fun appPassword(env: Map<String, String>): Result<Secret, ValidationError> =
            when (val secret = EnvSecretProvider(env).get(OrderConfig.APP_PASSWORD)) {
                is Result.Ok -> ok(secret.value)
                is Result.Err -> err(ValidationError.of(OrderConfig.APP_PASSWORD.value, secret.error.message))
            }

        private fun Application.orderApplication(
            koin: Koin,
            config: OrderConfig,
        ) {
            install(ServerObservability) {
                runtime = koin.get()
                integrationId = "INT-SALES-001"
            }
            installRequestDeadline(config.requestBudget)
            installProblemDetails { mapper = OrderProblems::mapper }
            install(Authentication) {
                eiaJwt {
                    verifier = koin.get()
                    realm = "eiaf"
                }
            }
            routing {
                overPlaintext { healthRoutes(koin.get()) }
                overTls { authenticate { orderRoutes(koin.get()) } }
            }
            launchPurgeJob(koin.get(), config)
        }

        private fun Route.healthRoutes(dataSource: HikariDataSource) {
            get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
            get("/health/ready") {
                val ready =
                    withContext(Dispatchers.IO) {
                        try {
                            dataSource.connection.use { it.isValid(READY_TIMEOUT_SECONDS) }
                        } catch (_: SQLException) {
                            false
                        }
                    }
                if (ready) {
                    call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
                } else {
                    call.respondProblem(Problem(ProblemType.SERVICE_UNAVAILABLE))
                }
            }
        }

        /** 期限切れの冪等の記録を、[OrderConfig.purgeInterval] ごとに消す(ADR-0022 §3)。サーバの停止で止まる。 */
        private fun Application.launchPurgeJob(
            store: IdempotencyStore,
            config: OrderConfig,
        ) {
            launch {
                while (isActive) {
                    delay(config.purgeInterval)
                    try {
                        val purged = store.purgeExpired(inProgressGrace = config.idempotencyLease)
                        if (purged > 0) logger.info("期限切れの冪等の記録を {} 件消しました", purged)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        // 次の周期でやり直す。例外のメッセージは SQL や接続先を含みうるため、型の名前だけを残す
                        logger.warn("期限切れの冪等の記録を消せませんでした(error.type={})", e::class.qualifiedName)
                    }
                }
            }
        }
    }
}
