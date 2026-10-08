package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.inbound.AclMetrics
import io.eia.legacyorderacl.adapters.inbound.LegacyChangeConsumer
import io.eia.legacyorderacl.adapters.inbound.LegacyChangeProcessor
import io.eia.legacyorderacl.adapters.inbound.OffsetCommitter
import io.eia.legacyorderacl.adapters.outbound.KafkaLegacyOrderStatePublisher
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.application.port.inbound.TranslateLegacyOrderChangeUseCase
import io.eia.legacyorderacl.application.port.outbound.LegacyOrderStatePublisher
import io.eia.legacyorderacl.application.usecase.TranslateLegacyOrderChangeService
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.koin.dsl.module

/**
 * legacy-order-acl の配線(Koin)。
 * 書き込むイベントのスキーマ ID は起動時に [SchemaIdBook] で解決し(ADR-0025 §3)、生の CDC の書き手のスキーマは contentId から取る
 * ([WriterSchemas]。新しい contentId を初めて見たときだけ問い合わせ、キャッシュする。ADR-0026 §4)。
 *
 * @param committer オフセットのコミット(統合テストで、発行の後・コミットの前に止める場合だけ差し替える)
 */
internal fun aclModule(
    config: AclConfig,
    runtime: ObservabilityRuntime,
    committer: OffsetCommitter?,
) = module {
    single { runtime }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(config.schemaRegistryUrl), get()) }
    single { SchemaIdBook(LegacyOrderEventSchemas.subjects, get()) }
    single { WriterSchemas(get()) }
    // 出力と DLQ で 1 つの Producer を共有する(冪等・acks=all。ADR-0026 §5)
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(config.bootstrapServers, clientId = "legacy-order-acl").toProperties())
    }
    single { EventProducer(get(), runtime, KafkaLegacyOrderStatePublisher.SOURCE) }
    single { DeadLetterPublisher(get()) }
    single<LegacyOrderStatePublisher> { KafkaLegacyOrderStatePublisher(get(), get()) }
    single<TranslateLegacyOrderChangeUseCase> { TranslateLegacyOrderChangeService(get()) }
    single { AclMetrics(runtime.meter) }
    single { LegacyChangeProcessor(get(), get(), get(), runtime, get()) }
    single {
        val consumer =
            KafkaConsumer<ByteArray?, ByteArray?>(LegacyChangeConsumer.consumerProperties(config.bootstrapServers, config.groupId))
        if (committer == null) LegacyChangeConsumer(consumer, get()) else LegacyChangeConsumer(consumer, get(), committer = committer)
    }
}
