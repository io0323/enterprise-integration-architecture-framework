package io.eia.platform.messagingkafka

import io.eia.platform.testsupport.KafkaBrokerContainer

// 適合: 統合テストの Kafka は KafkaBrokerContainer で立てる
class BrokerIT {
    val kafka = KafkaBrokerContainer()
}
