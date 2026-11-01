package io.eia.sample.adapters

import org.testcontainers.kafka.KafkaContainer

// 違反: テストでも KafkaContainer を直接作らない(KafkaBrokerContainer を使う)
class CreatesKafkaContainerIT {
    val kafka = KafkaContainer("apache/kafka:4.1.0")
}
