package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.inbound.LegacyChangeConsumer
import io.eia.legacyorderacl.adapters.inbound.OffsetCommitter
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
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
 * legacy-order-acl のプロセス(ADR-0026)。
 *
 * - 書き込むイベントのスキーマ ID を解決してから、生の CDC の読み取りを始める([LegacyChangeConsumer]。1 つのスレッドで動かす)。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、スキーマ ID をすべて解決し、
 *   パーティションを割り当てられ、直近の処理が一時的な失敗でないときだけ UP。
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
        koin.get<HttpClient>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private val logger = LoggerFactory.getLogger(AclServer::class.java)

        fun start(
            env: Map<String, String>,
            committer: OffsetCommitter? = null,
        ): Result<AclServer, ValidationError> =
            AclConfig.fromEnvironment(env).flatMap { config ->
                val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "legacy-order-acl") + env
                ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability ->
                    val runtime = Observability.init(observability)
                    val koin = koinApplication { modules(aclModule(config, runtime, committer)) }.koin
                    val schemaIds = koin.get<SchemaIdBook>()
                    val loop = koin.get<LegacyChangeConsumer>()
                    // Kafka の Consumer はスレッドセーフでないので、読み取りは 1 つのスレッドだけで行う
                    val consumerThread = Executors.newSingleThreadExecutor { Thread(it, "legacy-order-acl-consumer") }
                    val scope = CoroutineScope(SupervisorJob() + consumerThread.asCoroutineDispatcher())
                    scope.launch {
                        schemaIds.resolveUntilReady()
                        loop.run()
                    }
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
