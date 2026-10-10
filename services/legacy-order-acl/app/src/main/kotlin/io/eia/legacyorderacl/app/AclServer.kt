package io.eia.legacyorderacl.app

import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.OffsetCommitter
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.Producer
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * legacy-order-acl のプロセス(ADR-0026)。
 *
 * - 書き込むイベントのスキーマ ID を解決してから、生の CDC の読み取りを始める([EventConsumer]。1 つのスレッドで動かす)。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、スキーマ ID をすべて解決して読み取りを始め、
 *   直近の処理が一時的な失敗でないときだけ UP(処理の遅れは Consumer Group の lag で監視する。ADR-0026 §10)。
 */
internal class AclServer private constructor(
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

    /** 読み取りを止め、Consumer(読み取りのスレッドで閉じる)・Producer・サーバを閉じる。コミットしていない変更は、次の起動で送り直す。 */
    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        consumerThread.shutdown()
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<Producer<ByteArray, ByteArray>>().close()
        koin.getOrNull<Admin>()?.close()
        koin.get<HttpClient>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private val logger = LoggerFactory.getLogger(AclServer::class.java)

        /**
         * 照合をするなら、レガシーの DB のパスワード(SecretProvider)。照合をしないなら null。
         * 自動の再同期をするなら、signal 表に書くロールのパスワードも必須。
         */
        internal fun reconcileSecrets(
            config: AclConfig,
            env: Map<String, String>,
        ): Result<ReconcileSecrets?, ValidationError> {
            val reconcile = config.reconcile ?: return ok(null)
            val secrets = EnvSecretProvider(env)

            fun read(name: SecretName): Result<Secret, ValidationError> =
                when (val secret = secrets.get(name)) {
                    is Result.Ok -> ok(secret.value)
                    is Result.Err -> err(ValidationError.of(name.value, secret.error.message))
                }
            return read(ReconcileConfig.PASSWORD).flatMap { password ->
                if (reconcile.autoResync) {
                    read(ReconcileConfig.RESYNC_PASSWORD).map {
                        ReconcileSecrets(password, it)
                    }
                } else {
                    ok(ReconcileSecrets(password, null))
                }
            }
        }

        fun start(
            env: Map<String, String>,
            committer: OffsetCommitter? = null,
        ): Result<AclServer, ValidationError> =
            AclConfig
                .fromEnvironment(env)
                .flatMap { config ->
                    reconcileSecrets(config, env).flatMap { password ->
                        val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "legacy-order-acl") + env
                        ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> config to (password to observability) }
                    }
                }.map { (config, rest) ->
                    val (password, observability) = rest
                    run {
                        val runtime = Observability.init(observability)
                        val koin = koinApplication { modules(aclModule(config, runtime, committer, password)) }.koin
                        val schemaIds = koin.get<SchemaIdBook>()
                        val loop = koin.get<EventConsumer>()
                        // Kafka の Consumer はスレッドセーフでないので、読み取りは 1 つのスレッドだけで行う
                        val consumerThread = Executors.newSingleThreadExecutor { Thread(it, "legacy-order-acl-consumer") }
                        val scope = CoroutineScope(SupervisorJob() + consumerThread.asCoroutineDispatcher())
                        scope.launch {
                            schemaIds.resolveUntilReady()
                            loop.run()
                        }
                        // 照合(ADR-0027)は読み取りのスレッドとは別に動かす(待ちの間も変換を止めない)
                        koin.getOrNull<ReconcileJob>()?.let { job ->
                            scope.launch(Dispatchers.Default) {
                                schemaIds.resolveUntilReady()
                                job.run()
                            }
                        } ?: logger.warn("照合は無効です({} がない)", ReconcileConfig.DB_URL)
                        val server =
                            embeddedServer(Netty, configure = { connector { port = config.healthPort } }) {
                                routing {
                                    get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                                    get("/health/ready") {
                                        if (schemaIds.isReady && loop.ready) {
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
                        logger.info("legacy-order-acl を起動しました")
                        AclServer(server, koin, runtime, scope, consumerThread)
                    }
                }
    }
}
