package io.eia.shipping.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import io.eia.shipping.adapters.inbound.ShippingCommandHandlers
import io.eia.shipping.adapters.out.outbox.OutboxShippingReplies
import io.eia.shipping.adapters.out.outbox.ShippingEventSchemas
import io.eia.shipping.adapters.out.persistence.ExposedTransactionRunner
import io.eia.shipping.adapters.out.persistence.InboxProcessedCommands
import io.eia.shipping.adapters.out.persistence.JdbcShipmentStore
import io.eia.shipping.adapters.out.persistence.UuidV7ShipmentStamps
import io.eia.shipping.application.port.inbound.ArrangeShipmentUseCase
import io.eia.shipping.application.port.inbound.CancelShipmentUseCase
import io.eia.shipping.application.port.inbound.PurgeExpiredRecordsUseCase
import io.eia.shipping.application.port.outbound.ProcessedCommands
import io.eia.shipping.application.port.outbound.ShipmentStore
import io.eia.shipping.application.port.outbound.ShippingReplies
import io.eia.shipping.application.port.outbound.TransactionRunner
import io.eia.shipping.application.usecase.ArrangeShipmentService
import io.eia.shipping.application.usecase.CancelShipmentService
import io.eia.shipping.application.usecase.PurgeExpiredRecordsService
import io.eia.shipping.domain.ShippingRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

/**
 * shipping-service の配線(Koin)。
 * - 返事のスキーマ ID は起動時に [SchemaIdBook] で解決する(ADR-0025 §3)。コマンドの書き手のスキーマは contentId から取る([WriterSchemas])。
 * - DB はアプリのロールで接続する(所有者の権限は持たない)。
 *
 * @param committer オフセットのコミット(統合テストで、処理の後・コミットの前に止める場合だけ差し替える)
 */
internal fun shippingModule(
    config: ShippingConfig,
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
                poolName = "shipping"
            },
        )
    }
    single { Database.connect(get<HikariDataSource>()) }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(serve.schemaRegistryUrl), get()) }
    single { SchemaIdBook(ShippingEventSchemas.subjects, get()) }
    single { WriterSchemas(get()) }
    single { ShippingEventSchemas.Serializers(get()) }
    single { Outbox(OutboxMetrics(runtime.meter)) }
    single { OutboxEvents(runtime, SOURCE) }
    single<TransactionRunner> { ExposedTransactionRunner(get()) }
    single<ProcessedCommands> { InboxProcessedCommands(get(), ShippingCommandHandlers.GROUP_ID) }
    single<ShipmentStore> { JdbcShipmentStore(get()) }
    single<ShippingReplies> { OutboxShippingReplies(get(), get(), get(), get()) }
    single { ShippingRules(config.supportedCountries) }
    single<ArrangeShipmentUseCase> { ArrangeShipmentService(get(), get(), get(), get(), get(), UuidV7ShipmentStamps()) }
    single<CancelShipmentUseCase> { CancelShipmentService(get(), get(), get(), get(), get()) }
    single<PurgeExpiredRecordsUseCase> { PurgeExpiredRecordsService(get(), get(), config.retention) }
    single { ShippingCommandHandlers(get(), get()) }
    // DLQ だけに使う(返事は Outbox で発行する)
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(serve.bootstrapServers, clientId = "shipping-service").toProperties())
    }
    single {
        val consumer =
            KafkaConsumer<ByteArray?, ByteArray?>(
                EventConsumer.consumerProperties(serve.bootstrapServers, ShippingCommandHandlers.GROUP_ID, "shipping-service"),
            )
        val subscriptions = get<ShippingCommandHandlers>().subscriptions(get())
        val deadLetters = DeadLetterPublisher(get())
        if (committer == null) {
            EventConsumer(consumer, ShippingCommandHandlers.GROUP_ID, subscriptions, deadLetters, runtime)
        } else {
            EventConsumer(consumer, ShippingCommandHandlers.GROUP_ID, subscriptions, deadLetters, runtime, committer = committer)
        }
    }
}

/** 返事のイベントの `ce_source`。 */
internal const val SOURCE = "/shipping/shipping-service"
private const val POOL_SIZE = 5
