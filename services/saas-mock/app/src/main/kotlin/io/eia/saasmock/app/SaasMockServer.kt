package io.eia.saasmock.app

import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.map
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * saas-mock の常駐のプロセス。CRM の API と管理の API を出し、Webhook を送るループを回す。状態はメモリの上だけ。
 */
internal class SaasMockServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val client: HttpClient,
    private val scope: CoroutineScope,
) {
    private val stopped = CountDownLatch(1)

    fun awaitTermination() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        stopped.await()
    }

    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        client.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private val REQUEST_TIMEOUT = 10.seconds
        private val logger = LoggerFactory.getLogger(SaasMockServer::class.java)

        fun start(env: Map<String, String>): Result<SaasMockServer, ValidationError> {
            val secrets = EnvSecretProvider(env)
            return SaasMockConfig
                .fromEnvironment(env)
                .map { config ->
                    val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds } }
                    val dispatcher =
                        config.webhookUrl?.let { url ->
                            WebhookDispatcher(client, url.toString(), { webhookSecrets(secrets) }, config.retrySchedule)
                        }
                    val customers = CrmCustomers().also { seed(it, config.seedCustomers) }
                    val mock = SaasMock(customers, RateLimiter(config.rateLimit.requests, config.rateLimit.per), dispatcher, secrets)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    if (dispatcher != null) {
                        scope.launch {
                            while (currentCoroutineContext().isActive) {
                                dispatcher.dispatchDue()
                                delay(config.dispatchInterval)
                            }
                        }
                    }
                    val server =
                        embeddedServer(
                            Netty,
                            configure = { connector { port = config.port } },
                        ) { saasMock(mock) }.start(wait = false)
                    logger.info("saas-mock を起動しました(Webhook: {})", if (dispatcher != null) "送る" else "送らない")
                    requireSecrets(secrets, config)
                    SaasMockServer(server, client, scope)
                }
        }

        /** 起動の時に、必須の秘密情報が読めることを確かめる(値は残さない。使うたびに読む)。 */
        private fun requireSecrets(
            secrets: SecretProvider,
            config: SaasMockConfig,
        ) {
            listOf(SaasMockConfig.API_TOKEN, SaasMockConfig.ADMIN_TOKEN).forEach { name ->
                if (secrets.get(name) is Result.Err) logger.warn("{} を読めません(読めるまで 503 を返します)", name)
            }
            if (config.webhookUrl != null && webhookSecrets(secrets).isEmpty()) {
                logger.warn("{} を読めません(読めるまで Webhook を送りません)", SaasMockConfig.WEBHOOK_SECRET)
            }
        }

        /** 署名の鍵。今の鍵が読めなければ空(送らない)。次の鍵があれば両方で署名する。 */
        internal fun webhookSecrets(secrets: SecretProvider): List<Secret> {
            val current = (secrets.get(SaasMockConfig.WEBHOOK_SECRET) as? Result.Ok)?.value ?: return emptyList()
            val next = (secrets.get(SaasMockConfig.WEBHOOK_SECRET_NEXT) as? Result.Ok)?.value
            return listOfNotNull(current, next)
        }

        /** 定期の同期の確かめ用の顧客(Webhook は送らない)。 */
        internal fun seed(
            customers: CrmCustomers,
            count: Int,
        ) {
            for (n in 1..count) {
                customers.create(CustomerInput("cus_seed_%05d".format(n), "Seed Customer $n", "seed$n@example.com"))
            }
        }
    }
}
