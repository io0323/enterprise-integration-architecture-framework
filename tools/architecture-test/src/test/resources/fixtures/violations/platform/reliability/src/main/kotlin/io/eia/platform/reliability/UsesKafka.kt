package io.eia.platform.reliability

// 違反: Kafka のクライアントを platform/messaging-kafka の外で使う(ADR-0025 §5)
import org.apache.kafka.clients.producer.Producer

class UsesKafka(
    val producer: Producer<ByteArray, ByteArray>,
)
