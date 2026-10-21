package io.eia.integrationflow.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.integrationflow.adapters.FlowMetrics
import io.eia.integrationflow.adapters.flow.YamlFlowDefinitions
import io.eia.integrationflow.adapters.out.outbox.OutboxCanonicalPublisher
import io.eia.integrationflow.adapters.out.persistence.InboxProcessedMessages
import io.eia.integrationflow.adapters.out.saas.CrmCustomersConnector
import io.eia.integrationflow.application.port.inbound.SyncFlowUseCase
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.integrationflow.domain.Trigger
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.FieldViolation
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
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * integration-flow のプロセス(serve。ADR-0035・ADR-0036)。
 *
 * - 起動時に Flow の定義を検証し、誤りがあれば起動しない。届け先(Canonical の型とトピックの組)と、poll の trigger の Connector も確かめる。
 * - 書くイベントのスキーマ ID を解決してから、取り込みのトピックの購読([EventConsumer]。1 つのスレッド)と、poll の trigger ごとの定期の同期を始める。
 * - 保持期間を過ぎた冪等消費の記録を定期的に消す。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、スキーマ ID をすべて解決し、購読が Unavailable でないときだけ UP。
 */
internal class IntegrationFlowServer private constructor(
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

    /** 購読と同期を止め、Consumer(読み取りのスレッドで閉じる)・Producer・DB・サーバを閉じる。コミットしていないイベントは、次の起動で読み直す。 */
    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        consumerThread.shutdown()
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<Producer<ByteArray, ByteArray>>().close()
        listOf(REGISTRY_CLIENT, CRM_CLIENT).forEach { koin.get<HttpClient>(named(it)).close() }
        koin.get<HikariDataSource>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val REGISTRY_CLIENT = "registry"
        private const val CRM_CLIENT = "crm"
        private val logger = LoggerFactory.getLogger(IntegrationFlowServer::class.java)

        fun start(env: Map<String, String>): Result<IntegrationFlowServer, ValidationError> =
            serveInputs(env)
                .flatMap { inputs ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "integration-flow") + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> inputs to observability }
                }.map { (inputs, observability) ->
                    val runtime = Observability.init(observability)
                    val koin = koinApplication { modules(integrationFlowModule(inputs, runtime, EnvSecretProvider(env))) }.koin
                    val schemaIds = koin.get<SchemaIdBook>()
                    val consumer = koin.get<EventConsumer>()
                    // Kafka の Consumer はスレッドセーフでないので、読み取りは 1 つのスレッドだけで行う
                    val consumerThread = Executors.newSingleThreadExecutor { Thread(it, "integration-flow-consumer") }
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    scope.launch(consumerThread.asCoroutineDispatcher()) {
                        schemaIds.resolveUntilReady()
                        consumer.run()
                    }
                    launchSyncJobs(scope, koin, inputs, schemaIds)
                    launchPurgeJob(scope, koin.get(), inputs.config)
                    val server =
                        embeddedServer(Netty, configure = { connector { port = inputs.config.healthPort } }) {
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
                    logger.info("integration-flow を起動しました(Flow: {})", inputs.catalog.flows.joinToString { "${it.id}.v${it.version}" })
                    IntegrationFlowServer(server, koin, runtime, scope, consumerThread)
                }

        /** 設定・アプリのロールのパスワード・Flow の定義を読み、起動の前に確かめる。 */
        private fun serveInputs(env: Map<String, String>): Result<ServeInputs, ValidationError> =
            IntegrationFlowConfig
                .fromEnvironment(env)
                .flatMap { config -> config.forServe().map { serve -> config to serve } }
                .flatMap { (config, serve) ->
                    when (val password = EnvSecretProvider(env).get(IntegrationFlowConfig.APP_PASSWORD)) {
                        is Result.Ok -> ok(Triple(config, serve, password.value))
                        is Result.Err -> err(ValidationError.of(IntegrationFlowConfig.APP_PASSWORD.value, password.error.message))
                    }
                }.flatMap { (config, serve, password) ->
                    LoadFlowsService(YamlFlowDefinitions(config.flows))()
                        .flatMap { catalog -> checkRunnable(catalog, config) }
                        .map { catalog -> ServeInputs(config, serve, password, catalog) }
                }

        /** Flow の届け先を出せること・poll の trigger の Connector があること(Flow の定義の検証の後に、この実行の構成で確かめる)。 */
        internal fun checkRunnable(
            catalog: FlowCatalog,
            config: IntegrationFlowConfig,
        ): Result<FlowCatalog, ValidationError> {
            val violations =
                catalog.flows.flatMap { flow ->
                    val deliveries =
                        flow.routes.map { it.deliver }.distinct().filterNot { OutboxCanonicalPublisher.supports(flow, it) }.map {
                            FieldViolation("${flow.id}.v${flow.version}", "${flow.canonical} を $it に出せません(出せる組はコードの側に足す。ADR-0036 §2)")
                        }
                    val polls =
                        flow.triggers.filterIsInstance<Trigger.Poll>().mapNotNull { trigger ->
                            when {
                                !crmCustomers(trigger) -> {
                                    FieldViolation(
                                        "${flow.id}.v${flow.version}",
                                        "Connector ${trigger.connector} の ${trigger.resource} を読めません",
                                    )
                                }

                                config.crmApiUrl == null -> {
                                    FieldViolation(IntegrationFlowConfig.CRM_API_URL, "${flow.id} が ${trigger.connector} を定期に読むので必須です")
                                }

                                else -> {
                                    null
                                }
                            }
                        }
                    deliveries + polls
                }
            return if (violations.isEmpty()) ok(catalog) else err(ValidationError(violations))
        }

        private fun crmCustomers(trigger: Trigger.Poll): Boolean =
            trigger.connector == CrmCustomersConnector.CONNECTOR && trigger.resource == CrmCustomersConnector.CUSTOMERS

        /** poll の trigger ごとに、間隔([IntegrationFlowConfig.pollInterval] か Flow の every)で同期する。スキーマ ID を解決してから始める。 */
        private fun launchSyncJobs(
            scope: CoroutineScope,
            koin: Koin,
            inputs: ServeInputs,
            schemaIds: SchemaIdBook,
        ) {
            val sync = koin.get<SyncFlowUseCase>()
            val metrics = koin.get<FlowMetrics>()
            inputs.catalog.flows.forEach { flow ->
                flow.triggers.filterIsInstance<Trigger.Poll>().forEach { trigger ->
                    val interval = inputs.config.pollInterval ?: trigger.every
                    scope.launch {
                        schemaIds.resolveUntilReady()
                        while (isActive) {
                            when (val synced = sync(flow, trigger)) {
                                is Result.Ok -> {
                                    metrics.synced(flow.id, synced.value)
                                    logger.info("Flow {} を同期しました({})", flow.id, synced.value)
                                }

                                is Result.Err -> {
                                    metrics.synced(flow.id, null)
                                    logger.warn("Flow {} を同期できません({})。次の間隔でやり直します", flow.id, synced.error.code)
                                }
                            }
                            delay(interval)
                        }
                    }
                }
            }
        }

        /** 保持期間を過ぎた冪等消費の記録(14 日。ADR-0028 §3)を、なくなるまで少しずつ消す。 */
        private fun launchPurgeJob(
            scope: CoroutineScope,
            processed: InboxProcessedMessages,
            config: IntegrationFlowConfig,
        ) {
            scope.launch {
                while (isActive) {
                    delay(config.purgeInterval)
                    var total = 0
                    do {
                        val deleted = (processed.purgeExpired(PURGE_BATCH) as? Result.Ok)?.value ?: 0
                        total += deleted
                    } while (deleted >= PURGE_BATCH)
                    if (total > 0) logger.info("保持期間を過ぎた冪等消費の記録を {} 件消しました", total)
                }
            }
        }

        private const val PURGE_BATCH = 1000
    }
}
