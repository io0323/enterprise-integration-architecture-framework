package io.eia.sample.adapters

// 違反: 完全修飾名でも直接作らない
class CreatesKafkaContainerQualifiedIT {
    val kafka = org.testcontainers.kafka.KafkaContainer("apache/kafka:4.1.0")
}
