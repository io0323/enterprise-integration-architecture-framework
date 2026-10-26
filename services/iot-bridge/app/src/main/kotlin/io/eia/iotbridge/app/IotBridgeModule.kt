package io.eia.iotbridge.app

import io.eia.iotbridge.adapters.inbound.DevicePayloadDecoder
import io.eia.iotbridge.adapters.inbound.TelemetryDeadLetterRedelivery
import io.eia.iotbridge.adapters.inbound.TelemetryMessageHandler
import io.eia.iotbridge.adapters.inbound.TelemetryMetrics
import io.eia.iotbridge.adapters.outbound.IotEventSchemas
import io.eia.iotbridge.adapters.outbound.KafkaTelemetryPublisher
import io.eia.iotbridge.application.port.inbound.IngestTelemetryUseCase
import io.eia.iotbridge.application.usecase.IngestTelemetryService
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterPublisher.Companion.deadLetterTopic
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.koin.dsl.module

/**
 * iot-bridge の配線(Koin)。serve と reprocess-dlq で共有する(MQTT の購読は serve だけが作る。[IotBridgeServer])。
 * - 書くイベントのスキーマ ID は起動時に [SchemaIdBook] で解決する(ADR-0025 §3)。
 * - Kafka の Producer は 1 つで、テレメトリの発行と DLQ の隔離に使う(冪等・acks=all)。
 *
 * @param decoder 受け付ける形式の版(既定はリソースにあるすべての版)。統合テストで、デコーダを足す前を表すときだけ渡す
 */
internal fun iotBridgeModule(
    config: IotBridgeConfig,
    runtime: ObservabilityRuntime,
    clientId: String,
    decoder: DevicePayloadDecoder = DevicePayloadDecoder(),
) = module {
    single { runtime }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(config.schemaRegistryUrl), get()) }
    single { SchemaIdBook(IotEventSchemas.subjects, get<ApicurioRegistryClient>()) }
    single<Producer<ByteArray, ByteArray>> { KafkaProducer(KafkaProducerSettings(config.kafkaBootstrap, clientId).toProperties()) }
    single { KafkaTopics(get(), WRITTEN_TOPICS) }
    single<IngestTelemetryUseCase> {
        IngestTelemetryService(KafkaTelemetryPublisher(EventProducer(get(), runtime, KafkaTelemetryPublisher.SOURCE), get()))
    }
    single { decoder }
    single { TelemetryMessageHandler(get(), get(), DeadLetterPublisher(get()), runtime, TelemetryMetrics(runtime.meter)) }
    single { TelemetryDeadLetterRedelivery(get(), get()) }
}

/** iot-bridge が書くトピック(テレメトリと DLQ)。起動時に、どちらもあることを確かめる。 */
internal val WRITTEN_TOPICS: List<String> =
    listOf(IotEventSchemas.TELEMETRY_REPORTED.name, deadLetterTopic(IotEventSchemas.TELEMETRY_REPORTED.name))
