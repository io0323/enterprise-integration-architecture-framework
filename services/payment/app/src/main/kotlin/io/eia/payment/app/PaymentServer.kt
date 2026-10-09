package io.eia.payment.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.payment.application.port.inbound.PurgeExpiredRecordsUseCase
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.OffsetCommitter
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.Producer
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * payment-service のプロセス(注文 Saga の参加者。ADR-0029)。
 *
 * - 返事のスキーマ ID を解決してから、コマンドの読み取りを始める([EventConsumer]。1 つのスレッドで動かす)。
 * - 保持期間を過ぎた記録(終わった承認・印・冪等消費の記録)を定期的に消す(読み取りとは別のコルーチン)。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、スキーマ ID をすべて解決して読み取りを始め、
 *   直近の処理が Unavailable(DB に接続できないなど)でないときだけ UP(ADR-0028 §1)。
 */
internal class PaymentServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
    private val scope: CoroutineScope,
    private val consumerThread: ExecutorService,
) {
    private val stopped = CountDownLatch(1)

    val healthPort: Int get() =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }

    fun awaitTermination() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        stopped.await()
    }

    /** 読み取りを止め、Consumer(読み取りのスレッドで閉じる)・Producer・DB・サーバを閉じる。コミットしていないコマンドは、次の起動で読み直す。 */
    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        consumerThread.shutdown()
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<Producer<ByteArray, ByteArray>>().close()
        koin.get<HttpClient>().close()
        koin.get<HikariDataSource>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private val logger = LoggerFactory.getLogger(PaymentServer::class.java)

        fun start(
            env: Map<String, String>,
            committer: OffsetCommitter? = null,
        ): Result<PaymentServer, ValidationError> =
            PaymentConfig
                .fromEnvironment(env)
                .flatMap { config -> config.forServe().map { serve -> config to serve } }
                .flatMap { (config, serve) ->
                    when (val password = EnvSecretProvider(env).get(PaymentConfig.APP_PASSWORD)) {
                        is Result.Ok -> ok(Triple(config, serve, password.value))
                        is Result.Err -> err(ValidationError.of(PaymentConfig.APP_PASSWORD.value, password.error.message))
                    }
                }.flatMap { inputs ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "payment-service") + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> inputs to observability }
                }.map { (inputs, observability) ->
                    val (config, serve, password) = inputs
                    val runtime = Observability.init(observability)
                    val koin = koinApplication { modules(paymentModule(config, serve, password, runtime, committer)) }.koin
                    val schemaIds = koin.get<SchemaIdBook>()
                    val consumer = koin.get<EventConsumer>()
                    val purge = koin.get<PurgeExpiredRecordsUseCase>()
                    // Kafka の Consumer はスレッドセーフでないので、読み取りは 1 つのスレッドだけで行う
                    val consumerThread = Executors.newSingleThreadExecutor { Thread(it, "payment-consumer") }
                    val scope = CoroutineScope(SupervisorJob() + consumerThread.asCoroutineDispatcher())
                    scope.launch {
                        schemaIds.resolveUntilReady()
                        consumer.run()
                    }
                    scope.launch(Dispatchers.IO) {
                        while (isActive) {
                            when (val purged = purge()) {
                                is Result.Ok -> logger.info("保持期間を過ぎた記録を消しました({})", purged.value)
                                is Result.Err -> logger.warn("保持期間を過ぎた記録を消せません({})。次の間隔でやり直します", purged.error.code)
                            }
                            delay(config.purgeInterval)
                        }
                    }
                    val server =
                        embeddedServer(Netty, configure = { connector { port = config.healthPort } }) {
                            routing {
                                get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                                get("/health/ready") {
                                    if (schemaIds.isReady && consumer.ready) {
                                        call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
                                    } else {
                                        call.respondText(
                                            """{"status":"DOWN"}""",
                                            ContentType.Application.Json,
                                            HttpStatusCode.ServiceUnavailable,
                                        )
                                    }
                                }
                            }
                        }.start(wait = false)
                    logger.info("payment-service を起動しました")
                    PaymentServer(server, koin, runtime, scope, consumerThread)
                }
    }
}
