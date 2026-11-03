package io.eia.bffgraphql.adapters

// 違反: BFF はイベントを発行しない(ADR-0043 §8)
import io.eia.platform.messagingkafka.EventProducer
import org.apache.kafka.clients.producer.Producer

class BffUsesKafka(
    val producer: Producer<ByteArray, ByteArray>,
    val events: EventProducer,
)
