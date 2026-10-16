package io.eia.fileexchange.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.fileexchange.application.port.inbound.SweepInboxUseCase
import io.eia.platform.batch.BatchScheduler
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * file-exchange の常駐のプロセス(ADR-0032)。受け口のファイルの到着で受け取りの DAG を起動し([BatchScheduler])、受け口を定期的に見回る。
 *
 * - 止める(SIGTERM)と、実行中の受け取りはキャンセルされ RUNNING のまま残る。次の起動で、記録した移動の続きから再開する。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、DB に接続でき、起動の確認を始めているときだけ UP。
 */
internal class FileExchangeServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
    private val scope: CoroutineScope,
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

    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        close(koin, runtime)
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val VALID_TIMEOUT_SECONDS = 2
        private val logger = LoggerFactory.getLogger(FileExchangeServer::class.java)

        /** 設定・アプリのロールのパスワード・OTel を用意して、配線を作る。 */
        internal fun wire(
            env: Map<String, String>,
            serviceName: String = "file-exchange",
        ): Result<Pair<FileExchangeConfig, Pair<Koin, ObservabilityRuntime>>, ValidationError> =
            FileExchangeConfig
                .fromEnvironment(env)
                .flatMap { config -> config.forRun().map { endpoint -> config to endpoint } }
                .flatMap { (config, endpoint) ->
                    when (val password = EnvSecretProvider(env).get(FileExchangeConfig.APP_PASSWORD)) {
                        is Result.Ok -> ok(Triple(config, endpoint, password.value))
                        is Result.Err -> err(ValidationError.of(FileExchangeConfig.APP_PASSWORD.value, password.error.message))
                    }
                }.flatMap { inputs ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to serviceName) + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> inputs to observability }
                }.map { (inputs, observability) ->
                    val (config, endpoint, password) = inputs
                    val runtime = Observability.init(observability)
                    val koin =
                        koinApplication { modules(fileExchangeModule(config, endpoint, password, EnvSecretProvider(env), runtime)) }.koin
                    config to (koin to runtime)
                }

        internal fun close(
            koin: Koin,
            runtime: ObservabilityRuntime,
        ) {
            koin.get<S3ObjectStore>().close()
            koin.get<HikariDataSource>().close()
            koin.close()
            runtime.close()
        }

        fun start(env: Map<String, String>): Result<FileExchangeServer, ValidationError> =
            wire(env).map { (config, wired) ->
                val (koin, runtime) = wired
                val scheduler = koin.get<BatchScheduler>()
                val dataSource = koin.get<HikariDataSource>()
                val polling = AtomicBoolean(false)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                scope.launch {
                    polling.set(true)
                    scheduler.run()
                }
                scope.launch { sweepLoop(koin, config) }
                val server =
                    embeddedServer(Netty, configure = { connector { port = config.healthPort } }) {
                        routing {
                            get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                            get("/health/ready") {
                                if (polling.get() && databaseReachable(dataSource)) {
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
                logger.info("file-exchange を起動しました")
                FileExchangeServer(server, koin, runtime, scope)
            }

        /** 受け口を定期的に見回る(受け取り済みの名前で届いたファイル・滞留。ADR-0032 §5)。 */
        private suspend fun sweepLoop(
            koin: Koin,
            config: FileExchangeConfig,
        ) {
            val sweep = koin.get<SweepInboxUseCase>()
            val metrics = koin.get<FileExchangeMetrics>()
            while (currentCoroutineContext().isActive) {
                when (val report = sweep()) {
                    is Result.Ok -> metrics.swept(report.value)
                    is Result.Err -> logger.warn("受け口を見回れません({})", report.error.code)
                }
                delay(config.sweepInterval)
            }
        }

        @Suppress("TooGenericExceptionCaught") // 接続の失敗はどの種類でも DOWN にする
        private fun databaseReachable(dataSource: HikariDataSource): Boolean =
            try {
                dataSource.connection.use { it.isValid(VALID_TIMEOUT_SECONDS) }
            } catch (_: Exception) {
                false
            }
    }
}
