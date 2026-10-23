package io.eia.webhookreceiver.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.outbox.OutboxMetrics
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.security.secret.SecretProvider
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookMetrics
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookReceiver
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookSecrets
import io.eia.webhookreceiver.adapters.out.outbox.OutboxWebhookIngest
import io.eia.webhookreceiver.adapters.out.outbox.WebhookIngestSchemas
import io.eia.webhookreceiver.adapters.out.persistence.ExposedTransactionRunner
import io.eia.webhookreceiver.adapters.out.persistence.JdbcWebhookReceipts
import io.eia.webhookreceiver.application.port.inbound.PurgeReceiptsUseCase
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhookUseCase
import io.eia.webhookreceiver.application.port.outbound.IngestOutbox
import io.eia.webhookreceiver.application.port.outbound.WebhookReceipts
import io.eia.webhookreceiver.application.usecase.PurgeReceiptsService
import io.eia.webhookreceiver.application.usecase.ReceiveWebhookService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * webhook-receiver の配線(Koin)。
 * - 取り込みのイベントのスキーマ ID は起動時に [SchemaIdBook] で解決する(ADR-0025 §3)。
 * - DB はアプリのロールで接続する(所有者の権限は持たない)。署名の鍵は [secrets] から要求のたびに読む。
 */
internal fun webhookReceiverModule(
    inputs: ServeInputs,
    runtime: ObservabilityRuntime,
    secrets: SecretProvider,
    clock: Clock,
) = module {
    val config = inputs.config
    single { runtime }
    single {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.appUser
                password = inputs.appPassword.reveal()
                maximumPoolSize = POOL_SIZE
                poolName = "webhook-receiver"
            },
        )
    }
    single { Database.connect(get<HikariDataSource>()) }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(inputs.schemaRegistryUrl), get()) }
    single { SchemaIdBook(WebhookIngestSchemas.subjects, get()) }
    single { Outbox(OutboxMetrics(runtime.meter)) }
    single { OutboxEvents(runtime, SOURCE, clock) }
    single<WebhookReceipts> { JdbcWebhookReceipts(get()) }
    single<IngestOutbox> { OutboxWebhookIngest(get(), get(), get(), WebhookIngestSchemas.serializer(get())) }
    single<ReceiveWebhookUseCase> { ReceiveWebhookService(ExposedTransactionRunner(get()), get(), get(), clock) }
    single<PurgeReceiptsUseCase> { PurgeReceiptsService(get(), config.retention) }
    single {
        WebhookReceiver(
            receive = get(),
            secrets = WebhookSecrets(secrets, WebhookReceiverConfig.CRM_SECRET),
            metrics = WebhookMetrics(runtime.meter),
            clock = clock,
        )
    }
}

/** 取り込みのイベントの `ce_source`。 */
internal const val SOURCE = "/integration/webhook-receiver"

// 1 件ずつの処理と定期のジョブだけなので 3 本で足りる(すべての profile を同時に動かす E2E で DB の接続数が上限に届いた。P10 ⑦)
private const val POOL_SIZE = 3
