package io.eia.platform.messagingkafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Producer の設定(Framework 13.2 の At-Least-Once)。
 *
 * - `acks=all` と冪等な Producer(`enable.idempotence=true`)で、ブローカーの障害やリトライで欠落・重複しないようにする
 *   (Producer のリトライによる重複を防ぐ。アプリが送り直した場合の重複は、受信側の冪等で吸収する)。
 * - キーと値はバイト列で送る(値は [AvroEventSerializer] が作る。キーは UTF-8 の文字列)。
 *
 * @property bootstrapServers 例 `kafka:9092`
 * @property clientId Kafka の `client.id`(サービス名など。ブローカーのメトリクスとログで送り手を見分ける)
 * @property deliveryTimeout 送信を諦めるまでの時間(`delivery.timeout.ms`。リトライを含む)
 */
public data class KafkaProducerSettings(
    public val bootstrapServers: String,
    public val clientId: String,
    public val deliveryTimeout: Duration = DEFAULT_DELIVERY_TIMEOUT,
) {
    init {
        require(bootstrapServers.isNotBlank()) { "bootstrapServers が空です" }
        require(clientId.isNotBlank()) { "clientId が空です" }
        require(deliveryTimeout.isPositive()) { "deliveryTimeout は正の値にしてください" }
    }

    public fun toProperties(): Map<String, Any> =
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.CLIENT_ID_CONFIG to clientId,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to deliveryTimeout.inWholeMilliseconds.toInt(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
        )

    public companion object {
        /** Kafka の既定(2 分)。 */
        public val DEFAULT_DELIVERY_TIMEOUT: Duration = 2.minutes
    }
}
