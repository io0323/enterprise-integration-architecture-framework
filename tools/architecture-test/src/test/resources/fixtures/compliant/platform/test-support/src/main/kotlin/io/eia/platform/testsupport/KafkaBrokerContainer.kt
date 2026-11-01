package io.eia.platform.testsupport

import org.testcontainers.kafka.KafkaContainer

// 適合: test-support の中では KafkaContainer を継承して作る
class KafkaBrokerContainer : KafkaContainer("apache/kafka:4.1.0")
