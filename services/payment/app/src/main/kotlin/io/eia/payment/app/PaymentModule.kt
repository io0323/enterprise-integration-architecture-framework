package io.eia.payment.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.payment.adapters.inbound.PaymentCommandHandlers
import io.eia.payment.adapters.out.outbox.OutboxPaymentReplies
import io.eia.payment.adapters.out.outbox.PaymentEventSchemas
import io.eia.payment.adapters.out.persistence.ExposedTransactionRunner
import io.eia.payment.adapters.out.persistence.InboxProcessedCommands
import io.eia.payment.adapters.out.persistence.JdbcAuthorizationStore
import io.eia.payment.adapters.out.persistence.UuidV7AuthorizationIds
import io.eia.payment.application.port.inbound.AuthorizePaymentUseCase
import io.eia.payment.application.port.inbound.PurgeExpiredRecordsUseCase
import io.eia.payment.application.port.inbound.VoidPaymentUseCase
import io.eia.payment.application.port.outbound.AuthorizationStore
import io.eia.payment.application.port.outbound.PaymentReplies
import io.eia.payment.application.port.outbound.ProcessedCommands
import io.eia.payment.application.port.outbound.TransactionRunner
import io.eia.payment.application.usecase.AuthorizePaymentService
import io.eia.payment.application.usecase.PurgeExpiredRecordsService
import io.eia.payment.application.usecase.VoidPaymentService
import io.eia.payment.domain.PaymentRules
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
 * payment-service の配線(Koin)。
 * - 返事のスキーマ ID は起動時に [SchemaIdBook] で解決する(ADR-0025 §3)。コマンドの書き手のスキーマは contentId から取る([WriterSchemas])。
 * - DB はアプリのロールで接続する(所有者の権限は持たない)。
 *
 * @param committer オフセットのコミット(統合テストで、処理の後・コミットの前に止める場合だけ差し替える)
 */
internal fun paymentModule(
    config: PaymentConfig,
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
                poolName = "payment"
            },
        )
    }
    single { Database.connect(get<HikariDataSource>()) }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(serve.schemaRegistryUrl), get()) }
    single { SchemaIdBook(PaymentEventSchemas.subjects, get()) }
    single { WriterSchemas(get()) }
    single { PaymentEventSchemas.Serializers(get()) }
    single { Outbox(OutboxMetrics(runtime.meter)) }
    single { OutboxEvents(runtime, SOURCE) }
    single<TransactionRunner> { ExposedTransactionRunner(get()) }
    single<ProcessedCommands> { InboxProcessedCommands(get(), PaymentCommandHandlers.GROUP_ID) }
    single<AuthorizationStore> { JdbcAuthorizationStore(get()) }
    single<PaymentReplies> { OutboxPaymentReplies(get(), get(), get(), get()) }
    single { PaymentRules(config.limit) }
    single<AuthorizePaymentUseCase> { AuthorizePaymentService(get(), get(), get(), get(), get(), UuidV7AuthorizationIds()) }
    single<VoidPaymentUseCase> { VoidPaymentService(get(), get(), get(), get(), get()) }
    single<PurgeExpiredRecordsUseCase> { PurgeExpiredRecordsService(get(), get(), config.retention) }
    single { PaymentCommandHandlers(get(), get()) }
    // DLQ だけに使う(返事は Outbox で発行する)
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(serve.bootstrapServers, clientId = "payment-service").toProperties())
    }
    single {
        val consumer =
            KafkaConsumer<ByteArray?, ByteArray?>(
                EventConsumer.consumerProperties(serve.bootstrapServers, PaymentCommandHandlers.GROUP_ID, "payment-service"),
            )
        val subscriptions = get<PaymentCommandHandlers>().subscriptions(get())
        val deadLetters = DeadLetterPublisher(get())
        if (committer == null) {
            EventConsumer(consumer, PaymentCommandHandlers.GROUP_ID, subscriptions, deadLetters, runtime)
        } else {
            EventConsumer(consumer, PaymentCommandHandlers.GROUP_ID, subscriptions, deadLetters, runtime, committer = committer)
        }
    }
}

/** 返事のイベントの `ce_source`。 */
internal const val SOURCE = "/payment/payment-service"
private const val POOL_SIZE = 5
