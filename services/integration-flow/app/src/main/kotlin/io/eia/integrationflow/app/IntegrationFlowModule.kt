package io.eia.integrationflow.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.integrationflow.adapters.FlowMetrics
import io.eia.integrationflow.adapters.inbound.kafka.WebhookIngestHandler
import io.eia.integrationflow.adapters.out.outbox.FlowEventSchemas
import io.eia.integrationflow.adapters.out.outbox.OutboxCanonicalPublisher
import io.eia.integrationflow.adapters.out.persistence.ExposedTransactionRunner
import io.eia.integrationflow.adapters.out.persistence.InboxProcessedMessages
import io.eia.integrationflow.adapters.out.persistence.JdbcFlowVersions
import io.eia.integrationflow.adapters.out.persistence.JdbcSyncWatermarks
import io.eia.integrationflow.adapters.out.saas.CrmCustomersConnector
import io.eia.integrationflow.application.port.inbound.IngestWebhookUseCase
import io.eia.integrationflow.application.port.inbound.SyncFlowUseCase
import io.eia.integrationflow.application.port.outbound.SaasConnector
import io.eia.integrationflow.application.port.outbound.TransactionRunner
import io.eia.integrationflow.application.usecase.IngestWebhookService
import io.eia.integrationflow.application.usecase.RecordPublisher
import io.eia.integrationflow.application.usecase.SyncFlowService
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.outbox.OutboxMetrics
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.ResilienceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * integration-flow の配線(Koin)。
 * - 書くイベントのスキーマ ID は起動時に [SchemaIdBook] で解決する(ADR-0025 §3)。読むイベントの書き手のスキーマは contentId から取る。
 * - DB はアプリのロールで接続する(所有者の権限は持たない)。
 * - CRM の API の Resilience は依存先ごとに 1 つ(`saas-crm`)を ResilienceMetrics から作る(ADR-0021・#8-c)。429 の `Retry-After` は
 *   [CRM_RESILIENCE] の maxDelay(30 秒)まで待つ。
 */
internal fun integrationFlowModule(
    inputs: ServeInputs,
    runtime: ObservabilityRuntime,
    secrets: SecretProvider,
) = module {
    val config = inputs.config
    single { runtime }
    single { inputs.catalog }
    single {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.appUser
                password = inputs.appPassword.reveal()
                maximumPoolSize = POOL_SIZE
                poolName = "integration-flow"
            },
        )
    }
    single { Database.connect(get<HikariDataSource>()) }
    single(named(REGISTRY)) { HttpClient(CIO) }
    single(named(CRM)) { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(inputs.serve.schemaRegistryUrl), get(named(REGISTRY))) }
    single { SchemaIdBook(FlowEventSchemas.subjects, get()) }
    single { WriterSchemas(get()) }
    single { Outbox(OutboxMetrics(runtime.meter)) }
    single { OutboxEvents(runtime, SOURCE) }
    single { OutboxCanonicalPublisher(get(), get(), get(), FlowEventSchemas.customerChangedSerializer(get())) }
    single { FlowMetrics(runtime.meter) }
    single { ResilienceMetrics(runtime.meter) }
    single<TransactionRunner> { ExposedTransactionRunner(get()) }
    single { InboxProcessedMessages(get(), WebhookIngestHandler.GROUP_ID) }
    single { RecordPublisher(JdbcFlowVersions(get()), get<OutboxCanonicalPublisher>()) }
    single<IngestWebhookUseCase> { IngestWebhookService(get(), get(), get<InboxProcessedMessages>(), get()) }
    single<Map<String, SaasConnector>> {
        listOfNotNull(
            config.crmApiUrl?.let { url -> crmConnector(get(named(CRM)), url, secrets, get()) },
        ).associateBy { it.connector }
    }
    single<SyncFlowUseCase> { SyncFlowService(get(), get(), get(), JdbcSyncWatermarks(get())) }
    single { WebhookIngestHandler(get(), get(), get()) }
    // DLQ だけに使う(Canonical のイベントは Outbox で発行する)
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(inputs.serve.kafkaBootstrap, clientId = "integration-flow").toProperties())
    }
    single {
        val consumer =
            KafkaConsumer<ByteArray?, ByteArray?>(
                EventConsumer.consumerProperties(inputs.serve.kafkaBootstrap, WebhookIngestHandler.GROUP_ID, "integration-flow"),
            )
        EventConsumer(
            consumer,
            WebhookIngestHandler.GROUP_ID,
            get<WebhookIngestHandler>().subscriptions(get()),
            DeadLetterPublisher(get()),
            runtime,
        )
    }
}

private fun crmConnector(
    client: HttpClient,
    url: URI,
    secrets: SecretProvider,
    metrics: ResilienceMetrics,
): CrmCustomersConnector =
    CrmCustomersConnector(
        client,
        CrmCustomersConnector.CrmApi(url, IntegrationFlowConfig.CRM_API_TOKEN),
        secrets,
        metrics.resilience(CrmCustomersConnector.RESILIENCE_NAME, CRM_RESILIENCE),
    )

/** CRM の API(SaaS)の Resilience。429 の Retry-After を待てるよう、待ち時間の上限と全体の締め切りを長めにする(ADR-0035 §6)。 */
internal val CRM_RESILIENCE: ResilienceConfig =
    ResilienceConfig(
        attemptTimeout = 10.seconds,
        deadline = 2.minutes,
        retry = RetryPolicy(initialDelay = 1.seconds, maxAttempts = 4, maxDelay = 30.seconds),
    )

/** 発行するイベントの `ce_source`。 */
internal const val SOURCE = "/integration/integration-flow"
// 1 件ずつの処理と定期のジョブだけなので 3 本で足りる(すべての profile を同時に動かす E2E で DB の接続数が上限に届いた。P10 ⑦)
private const val POOL_SIZE = 3
private const val REGISTRY = "registry"
private const val CRM = "crm"
