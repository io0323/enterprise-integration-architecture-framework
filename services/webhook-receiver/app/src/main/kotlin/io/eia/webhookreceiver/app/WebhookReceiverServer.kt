package io.eia.webhookreceiver.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.platform.api.deadline.installRequestDeadline
import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.mutualTlsConnectors
import io.eia.platform.security.tls.overPlaintext
import io.eia.platform.security.tls.overTls
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookReceiver
import io.eia.webhookreceiver.adapters.inbound.rest.webhookReceiver
import io.eia.webhookreceiver.application.port.inbound.PurgeReceiptsUseCase
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
import kotlin.time.Clock

/**
 * webhook-receiver のプロセス(serve。Ktor の Netty。ADR-0024・ADR-0035)。
 *
 * - 受け口のポート([httpsPort]): mTLS だけで受ける(ゲートウェイの証明書。ADR-0024 §6)。ルートは `POST /v1/webhooks/crm` だけ。
 *   認証は JWT ではなく Webhook の署名(ADR-0035 §2)。
 * - ヘルスチェックのポート([healthPort]): 平文で `/health/live` と `/health/ready` だけ。ready は、取り込みのイベントのスキーマ ID を
 *   解決し、DB に接続できるときだけ UP(解決する前に受け付けると、Outbox に書けずに 503 を返し続けるため。ADR-0025 §3)。
 * - 保持期間を過ぎた受け取りの記録を定期的に消す。
 */
internal class WebhookReceiverServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
) {
    private val stopped = CountDownLatch(1)

    val httpsPort: Int get() = resolvedPort(ConnectorType.HTTPS)

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
        koin.get<HttpClient>().close()
        koin.get<HikariDataSource>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val READY_TIMEOUT_SECONDS = 2
        private const val CRM_PATH = "/v1/webhooks/crm"
        private const val CRM_SOURCE = "crm"
        private val logger = LoggerFactory.getLogger(WebhookReceiverServer::class.java)

        /**
         * 設定を読み、サーバを起動する。所有者のパスワードは呼び出し元(serve のコマンド)が拒否する。
         *
         * @param clock 受け口の時刻(署名の時刻の許容の判定と、受け取りの時刻)。統合テストで進める
         */
        fun start(
            env: Map<String, String>,
            clock: Clock = Clock.System,
        ): Result<WebhookReceiverServer, ValidationError> =
            serveInputs(env).flatMap { inputs ->
                val config = inputs.config
                ServerTls.load(config.tls).flatMap { tls ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "webhook-receiver") + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability ->
                        val runtime = Observability.init(observability)
                        val module = webhookReceiverModule(inputs, runtime, EnvSecretProvider(env), clock)
                        val koin = koinApplication { modules(module) }.koin
                        val server =
                            embeddedServer(
                                Netty,
                                configure = { mutualTlsConnectors(config.httpsPort, config.healthPort, tls, config.allowedClients) },
                            ) { receiverApplication(koin, config) }.start(wait = false)
                        logger.info("webhook-receiver を起動しました")
                        WebhookReceiverServer(server, koin, runtime)
                    }
                }
            }

        private fun serveInputs(env: Map<String, String>): Result<ServeInputs, ValidationError> =
            WebhookReceiverConfig.fromEnvironment(env).flatMap { config ->
                val registry =
                    config.schemaRegistryUrl
                        ?: return@flatMap err(
                            ValidationError(listOf(FieldViolation(WebhookReceiverConfig.SCHEMA_REGISTRY_URL, "serve には必須です"))),
                        )
                when (val password = EnvSecretProvider(env).get(WebhookReceiverConfig.APP_PASSWORD)) {
                    is Result.Ok -> ok(ServeInputs(config, registry, password.value))
                    is Result.Err -> err(ValidationError.of(WebhookReceiverConfig.APP_PASSWORD.value, password.error.message))
                }
            }

        private fun Application.receiverApplication(
            koin: Koin,
            config: WebhookReceiverConfig,
        ) {
            install(ServerObservability) {
                runtime = koin.get()
                integrationId = "INT-SALES-005"
            }
            installRequestDeadline(config.requestBudget)
            installProblemDetails()
            val schemaIds = koin.get<SchemaIdBook>()
            val dataSource = koin.get<HikariDataSource>()
            routing {
                overPlaintext {
                    get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                    get("/health/ready") {
                        if (schemaIds.isReady && databaseReachable(dataSource)) {
                            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
                        } else {
                            call.respondProblem(Problem(ProblemType.SERVICE_UNAVAILABLE))
                        }
                    }
                }
                overTls { webhookReceiver(CRM_PATH, CRM_SOURCE, koin.get<WebhookReceiver>()) }
            }
            // 書き込むイベントのスキーマ ID を解決する。解決するまで /health/ready は 503(ADR-0025 §3)
            launch { schemaIds.resolveUntilReady() }
            launchPurgeJob(koin.get(), config)
        }

        /** 保持期間を過ぎた受け取りの記録を、[WebhookReceiverConfig.purgeInterval] ごとに消す。サーバの停止で止まる。 */
        private fun Application.launchPurgeJob(
            purge: PurgeReceiptsUseCase,
            config: WebhookReceiverConfig,
        ) {
            launch {
                while (isActive) {
                    delay(config.purgeInterval)
                    when (val purged = purge()) {
                        is Result.Ok -> if (purged.value > 0) logger.info("保持期間を過ぎた受け取りの記録を {} 件消しました", purged.value)
                        is Result.Err -> logger.warn("受け取りの記録を消せません({})。次の間隔でやり直します", purged.error.code)
                    }
                }
            }
        }

        private suspend fun databaseReachable(dataSource: HikariDataSource): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    dataSource.connection.use { it.isValid(READY_TIMEOUT_SECONDS) }
                } catch (_: SQLException) {
                    false
                }
            }
    }
}

/** serve の設定と、起動の前に確かめた値(Schema Registry の URL・アプリのロールのパスワード)。 */
internal data class ServeInputs(
    val config: WebhookReceiverConfig,
    val schemaRegistryUrl: String,
    val appPassword: Secret,
)
