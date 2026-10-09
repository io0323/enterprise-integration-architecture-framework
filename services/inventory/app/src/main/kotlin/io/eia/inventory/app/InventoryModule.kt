package io.eia.inventory.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.inventory.adapters.inbound.InventoryCommandHandlers
import io.eia.inventory.adapters.out.outbox.InventoryEventSchemas
import io.eia.inventory.adapters.out.outbox.OutboxInventoryReplies
import io.eia.inventory.adapters.out.persistence.ExposedTransactionRunner
import io.eia.inventory.adapters.out.persistence.InboxProcessedCommands
import io.eia.inventory.adapters.out.persistence.JdbcReservationStore
import io.eia.inventory.adapters.out.persistence.JdbcStockLedger
import io.eia.inventory.application.port.inbound.PurgeExpiredRecordsUseCase
import io.eia.inventory.application.port.inbound.ReleaseStockUseCase
import io.eia.inventory.application.port.inbound.ReserveStockUseCase
import io.eia.inventory.application.port.outbound.InventoryReplies
import io.eia.inventory.application.port.outbound.ProcessedCommands
import io.eia.inventory.application.port.outbound.ReservationStore
import io.eia.inventory.application.port.outbound.StockLedger
import io.eia.inventory.application.port.outbound.TransactionRunner
import io.eia.inventory.application.usecase.PurgeExpiredRecordsService
import io.eia.inventory.application.usecase.ReleaseStockService
import io.eia.inventory.application.usecase.ReserveStockService
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.messagingkafka.OffsetCommitter
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.outbox.OutboxMetrics
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.secret.Secret
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

/**
 * inventory-service の配線(Koin)。
 * - 返事のスキーマ ID は起動時に [SchemaIdBook] で解決する(ADR-0025 §3)。コマンドの書き手のスキーマは contentId から取る([WriterSchemas])。
 * - DB はアプリのロールで接続する(所有者の権限は持たない)。
 *
 * @param committer オフセットのコミット(統合テストで、処理の後・コミットの前に止める場合だけ差し替える)
 */
internal fun inventoryModule(
    config: InventoryConfig,
    serve: ServeSettings,
    appPassword: Secret,
    runtime: ObservabilityRuntime,
    committer: OffsetCommitter? = null,
) = module {
    single { runtime }
    single {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.appUser
                password = appPassword.reveal()
                maximumPoolSize = POOL_SIZE
                poolName = "inventory"
            },
        )
    }
    single { Database.connect(get<HikariDataSource>()) }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(serve.schemaRegistryUrl), get()) }
    single { SchemaIdBook(InventoryEventSchemas.subjects, get()) }
    single { WriterSchemas(get()) }
    single { InventoryEventSchemas.Serializers(get()) }
    single { Outbox(OutboxMetrics(runtime.meter)) }
    single { OutboxEvents(runtime, SOURCE) }
    single<TransactionRunner> { ExposedTransactionRunner(get()) }
    single<ProcessedCommands> { InboxProcessedCommands(get(), InventoryCommandHandlers.GROUP_ID) }
    single<ReservationStore> { JdbcReservationStore(get()) }
    single<StockLedger> { JdbcStockLedger(get()) }
    single<InventoryReplies> { OutboxInventoryReplies(get(), get(), get(), get()) }
    single<ReserveStockUseCase> { ReserveStockService(get(), get(), get(), get(), get()) }
    single<ReleaseStockUseCase> { ReleaseStockService(get(), get(), get(), get(), get()) }
    single<PurgeExpiredRecordsUseCase> { PurgeExpiredRecordsService(get(), get(), config.retention) }
    single { InventoryCommandHandlers(get(), get()) }
    // DLQ だけに使う(返事は Outbox で発行する)
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(serve.bootstrapServers, clientId = "inventory-service").toProperties())
    }
    single {
        val consumer =
            KafkaConsumer<ByteArray?, ByteArray?>(
                EventConsumer.consumerProperties(serve.bootstrapServers, InventoryCommandHandlers.GROUP_ID, "inventory-service"),
            )
        val subscriptions = get<InventoryCommandHandlers>().subscriptions(get())
        val deadLetters = DeadLetterPublisher(get())
        if (committer == null) {
            EventConsumer(consumer, InventoryCommandHandlers.GROUP_ID, subscriptions, deadLetters, runtime)
        } else {
            EventConsumer(consumer, InventoryCommandHandlers.GROUP_ID, subscriptions, deadLetters, runtime, committer = committer)
        }
    }
}

/** 返事のイベントの `ce_source`。 */
internal const val SOURCE = "/inventory/inventory-service"
private const val POOL_SIZE = 5
