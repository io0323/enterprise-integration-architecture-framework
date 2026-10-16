package io.eia.batchetl.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.batchetl.adapters.file.S3SalesFileStore
import io.eia.batchetl.adapters.http.HttpOrderSummarySource
import io.eia.batchetl.adapters.kafka.KafkaIngestPositions
import io.eia.batchetl.adapters.kafka.KafkaOrderEventSource
import io.eia.batchetl.adapters.persistence.JdbcDailySalesLines
import io.eia.batchetl.adapters.persistence.JdbcMartSummaries
import io.eia.batchetl.adapters.persistence.JdbcOrderMart
import io.eia.batchetl.adapters.persistence.JdbcRawOrderStore
import io.eia.batchetl.application.port.inbound.BuildDailyOrderMartUseCase
import io.eia.batchetl.application.port.inbound.ExportDailySalesFileUseCase
import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.port.inbound.ReconcileOrderMartUseCase
import io.eia.batchetl.application.usecase.BuildDailyOrderMartService
import io.eia.batchetl.application.usecase.ExportDailySalesFileService
import io.eia.batchetl.application.usecase.IngestOrderEventsService
import io.eia.batchetl.application.usecase.ReconcileOrderMartService
import io.eia.platform.batch.BatchMetrics
import io.eia.platform.batch.BatchRunner
import io.eia.platform.batch.BatchScheduler
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.batch.sla.SlaMonitor
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.client.ClientObservability
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.resilience.ResilienceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * batch-etl の配線(Koin)。DB はアプリのロールで接続する(所有者の権限は持たない)。
 * 実行のロック(advisory lock)は実行の間、接続を 1 つ持ち続けるので、プールは同時に動く実行の数より大きくする(ADR-0030 §2)。
 */
internal fun batchEtlModule(
    config: BatchEtlConfig,
    run: RunSettings,
    appPassword: Secret,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) = module {
    single { runtime }
    single {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.appUser
                password = appPassword.reveal()
                maximumPoolSize = POOL_SIZE
                poolName = "batch-etl"
            },
        )
    }
    single { JdbcBatchStore(get<HikariDataSource>()) }
    single { HttpClient(CIO) }
    single { WriterSchemas(ApicurioRegistryClient(SchemaRegistryConfig(run.schemaRegistryUrl), get())) }
    // DLQ だけに使う
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(run.bootstrapServers, clientId = "batch-etl").toProperties())
    }
    single {
        KafkaOrderEventSource(
            consumers = { KafkaConsumer(KafkaOrderEventSource.consumerProperties(run.bootstrapServers)) },
            writerSchemas = get(),
            deadLetters = DeadLetterPublisher(get()),
        )
    }
    single<Admin> { Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to run.bootstrapServers)) }
    single { KafkaIngestPositions({ KafkaConsumer(KafkaOrderEventSource.consumerProperties(run.bootstrapServers)) }, get()) }
    single<IngestOrderEventsUseCase> {
        IngestOrderEventsService(get<KafkaOrderEventSource>(), JdbcRawOrderStore(get<HikariDataSource>(), get()))
    }
    single<BuildDailyOrderMartUseCase> { BuildDailyOrderMartService(JdbcOrderMart(get<HikariDataSource>())) }
    single { BatchEtlMetrics(runtime.meter) }
    config.reconcile?.let { includes(reconcileModule(it, secrets, runtime)) }
    config.salesFile?.let { includes(salesFileModule(it, secrets)) }
    single(DAILY) { OrderDailyJob.dag(get(), get(), get(), config.slaAt) }
    single { BatchRunner(get<JdbcBatchStore>(), runtime, listener = BatchMetrics(runtime.meter)) }
    single {
        val jobs =
            listOfNotNull(
                OrderDailyJob.job(get(DAILY), config.dailyAt),
                config.reconcile?.let { OrderReconcileJob.job(get(RECONCILE), it.weeklyOn, it.at) },
                config.salesFile?.let { SalesFileJob.job(get(SALES_FILE)) },
            )
        val store = get<JdbcBatchStore>()
        BatchScheduler(
            runner = get(),
            store = store,
            jobs = jobs,
            monitor = SlaMonitor(jobs, store, meter = runtime.meter),
            settings = BatchScheduler.Settings(pollInterval = config.pollInterval),
        )
    }
}

/**
 * 週次の照合(ADR-0031 §6)の配線。order-service の日次の集計は API Gateway 経由で読む(Client Credentials のトークン。
 * Timeout・Retry・Circuit Breaker。traceparent と Correlation ID を送る)。
 */
internal fun reconcileModule(
    reconcile: ReconcileSettings,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) = module {
    single(RECONCILE) { HttpClient(CIO) { install(ClientObservability) { this.runtime = runtime } } }
    single { ResilienceMetrics(runtime.meter) }
    single {
        ClientCredentialsTokenProvider(
            ClientCredentialsConfig(
                reconcile.tokenUrl,
                reconcile.clientId,
                BatchEtlConfig.CLIENT_SECRET,
                setOf(HttpOrderSummarySource.SCOPE),
            ),
            get(RECONCILE),
            secrets,
            resilience =
                get<ResilienceMetrics>().resilience(
                    ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME,
                    ClientCredentialsTokenProvider.DEFAULT_RESILIENCE,
                ),
        )
    }
    single {
        HttpOrderSummarySource(
            client = get(RECONCILE),
            gatewayUrl = reconcile.gatewayUrl,
            tokens = get(),
            // 1 日の集計は、その日の注文を全部読んでハッシュを取る(数秒)。38 日分を順に問い合わせる
            resilience =
                get<ResilienceMetrics>().resilience(
                    "order-service",
                    ResilienceConfig(attemptTimeout = 30.seconds, deadline = 2.minutes),
                ),
        )
    }
    single { JdbcMartSummaries(get<HikariDataSource>()) }
    single { ReconcileMetrics(runtime.meter) }
    single<ReconcileOrderMartUseCase> {
        ReconcileOrderMartService(
            ingest = get(),
            mart = JdbcOrderMart(get<HikariDataSource>()),
            martSummaries = get<JdbcMartSummaries>(),
            source = get<HttpOrderSummarySource>(),
            log = get<JdbcMartSummaries>(),
        )
    }
    single(RECONCILE) { OrderReconcileJob.dag(get(), get()) }
}

/**
 * 日次の売上ファイル(INT-SALES-004。ADR-0031 §8)の配線。置き場は受け口に書くだけの identity で使う(ADR-0032 §1)。
 * 一時ファイルは java.io.tmpdir(distroless では /tmp)に書く。
 */
internal fun salesFileModule(
    settings: SalesFileSettings,
    secrets: SecretProvider,
) = module {
    single {
        S3ObjectStore(
            S3StoreConfig(settings.endpoint, settings.bucket, BatchEtlConfig.FILE_S3_ACCESS_KEY, BatchEtlConfig.FILE_S3_SECRET_KEY),
            secrets,
        )
    }
    single<ExportDailySalesFileUseCase> {
        ExportDailySalesFileService(
            lines = JdbcDailySalesLines(get<HikariDataSource>()),
            store = S3SalesFileStore(FilePublisher(get<S3ObjectStore>()), settings.prefix),
            formats = settings.formats,
        )
    }
    single(SALES_FILE) { SalesFileJob.dag(get(), settings.slaAt) }
}

internal val DAILY = named("daily")
internal val RECONCILE = named("reconcile")
internal val SALES_FILE = named("sales-file")
private const val POOL_SIZE = 5
