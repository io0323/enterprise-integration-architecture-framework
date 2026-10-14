package io.eia.batchetl.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.batchetl.adapters.kafka.KafkaOrderEventSource
import io.eia.batchetl.adapters.persistence.JdbcOrderMart
import io.eia.batchetl.adapters.persistence.JdbcRawOrderStore
import io.eia.batchetl.application.port.inbound.BuildDailyOrderMartUseCase
import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.usecase.BuildDailyOrderMartService
import io.eia.batchetl.application.usecase.IngestOrderEventsService
import io.eia.platform.batch.BatchMetrics
import io.eia.platform.batch.BatchRunner
import io.eia.platform.batch.BatchScheduler
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.batch.sla.SlaMonitor
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.secret.Secret
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.koin.dsl.module

/**
 * batch-etl の配線(Koin)。DB はアプリのロールで接続する(所有者の権限は持たない)。
 * 実行のロック(advisory lock)は実行の間、接続を 1 つ持ち続けるので、プールは同時に動く実行の数より大きくする(ADR-0030 §2)。
 */
internal fun batchEtlModule(
    config: BatchEtlConfig,
    run: RunSettings,
    appPassword: Secret,
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
    single<IngestOrderEventsUseCase> {
        IngestOrderEventsService(get<KafkaOrderEventSource>(), JdbcRawOrderStore(get<HikariDataSource>(), get()))
    }
    single<BuildDailyOrderMartUseCase> { BuildDailyOrderMartService(JdbcOrderMart(get<HikariDataSource>())) }
    single { BatchEtlMetrics(runtime.meter) }
    single { OrderDailyJob.dag(get(), get(), get(), config.slaAt) }
    single { BatchRunner(get<JdbcBatchStore>(), runtime, listener = BatchMetrics(runtime.meter)) }
    single {
        val jobs = listOf(OrderDailyJob.job(get(), config.dailyAt))
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

private const val POOL_SIZE = 5
