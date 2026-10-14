package io.eia.batchetl.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.batchetl.adapters.kafka.KafkaIngestPositions
import io.eia.batchetl.adapters.persistence.JdbcRawOrderStore
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.platform.batch.BatchScheduler
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrNull
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.Producer
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

/**
 * batch-etl の常駐のプロセス(ADR-0031)。日次の DAG を [BatchScheduler] で起動し、SLA の予測を更新する(ADR-0030 §4・§6)。
 *
 * - 止める(SIGTERM)と、実行中の実行はキャンセルされ RUNNING のまま残る。次の起動で、落ちたステップから再開する。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、DB に接続でき、起動の確認を始めているときだけ UP。
 */
internal class BatchEtlServer private constructor(
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
        private val logger = LoggerFactory.getLogger(BatchEtlServer::class.java)

        /** serve・run の設定・アプリのロールのパスワード・OTel を用意して、配線を作る。 */
        internal fun wire(
            env: Map<String, String>,
            serviceName: String = "batch-etl",
        ): Result<Pair<BatchEtlConfig, Pair<Koin, ObservabilityRuntime>>, ValidationError> =
            BatchEtlConfig
                .fromEnvironment(env)
                .flatMap { config -> config.forRun().map { run -> config to run } }
                .flatMap { (config, run) ->
                    val secrets = EnvSecretProvider(env)
                    when (val password = secrets.get(BatchEtlConfig.APP_PASSWORD)) {
                        is Result.Ok -> clientSecretPresent(config, secrets).map { Triple(config, run, password.value) }
                        is Result.Err -> err(ValidationError.of(BatchEtlConfig.APP_PASSWORD.value, password.error.message))
                    }
                }.flatMap { inputs ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to serviceName) + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> inputs to observability }
                }.map { (inputs, observability) ->
                    val (config, run, password: Secret) = inputs
                    val runtime = Observability.init(observability)
                    val koin = koinApplication { modules(batchEtlModule(config, run, password, EnvSecretProvider(env), runtime)) }.koin
                    config to (koin to runtime)
                }

        /** 照合をするなら、Client Secret が読めること(起動の時に確かめる。値は持たない。取得のたびに読む)。 */
        private fun clientSecretPresent(
            config: BatchEtlConfig,
            secrets: EnvSecretProvider,
        ): Result<Unit, ValidationError> {
            if (config.reconcile == null) return ok(Unit)
            return when (val secret = secrets.get(BatchEtlConfig.CLIENT_SECRET)) {
                is Result.Ok -> ok(Unit)
                is Result.Err -> err(ValidationError.of(BatchEtlConfig.CLIENT_SECRET.value, secret.error.message))
            }
        }

        internal fun close(
            koin: Koin,
            runtime: ObservabilityRuntime,
        ) {
            koin.get<Producer<ByteArray, ByteArray>>().close()
            koin.get<Admin>().close()
            koin.get<HttpClient>().close()
            koin.getOrNull<HttpClient>(RECONCILE)?.close()
            koin.get<HikariDataSource>().close()
            koin.close()
            runtime.close()
        }

        fun start(env: Map<String, String>): Result<BatchEtlServer, ValidationError> =
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
                scope.launch { probeLag(koin, config) }
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
                if (config.reconcile == null) logger.warn("週次の照合は無効です({} がない)", BatchEtlConfig.GATEWAY_URL)
                logger.info("batch-etl を起動しました")
                BatchEtlServer(server, koin, runtime, scope)
            }

        /** 取り込みの遅れ(保持期間に対して)を定期的に確かめ、メトリクスに出す(ADR-0031 §7)。 */
        private suspend fun probeLag(
            koin: Koin,
            config: BatchEtlConfig,
        ) {
            val positions = koin.get<KafkaIngestPositions>()
            val store = koin.get<JdbcBatchStore>()
            val metrics = koin.get<BatchEtlMetrics>()
            while (currentCoroutineContext().isActive) {
                val stored = withContext(Dispatchers.IO) { store.readCheckpoint(JdbcRawOrderStore.WATERMARK) }.getOrNull()
                when (val lag = positions.lag(stored?.let(::SourcePosition), Clock.System.now())) {
                    is Result.Ok -> metrics.lag(lag.value)
                    is Result.Err -> logger.warn("取り込みの遅れを確かめられません({})", lag.error.code)
                }
                delay(config.lagProbeInterval)
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
