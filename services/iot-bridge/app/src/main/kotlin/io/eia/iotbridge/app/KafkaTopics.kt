package io.eia.iotbridge.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 書くトピックが Kafka にあり、メタデータを取れるか(Kafka に送れるか)を、購読を始める前に確かめる。
 * Kafka の自動のトピックの作成は使わない(トピックは `infra/local/kafka/topics.conf` で作る)ので、ないトピックに送ると、
 * Producer はメタデータを待って送れない。確かめる前に MQTT を購読すると、PUBACK を返せないメッセージを受け取り続けることになる。
 */
internal class KafkaTopics(
    private val producer: Producer<ByteArray, ByteArray>,
    private val topics: List<String>,
) {
    @Volatile
    var isReady: Boolean = false
        private set

    /** すべてのトピックのメタデータを取れるまで、[retryInterval] ごとに確かめる。 */
    suspend fun awaitUntilReady(retryInterval: Duration = DEFAULT_RETRY_INTERVAL) {
        while (!check()) {
            logger.warn("Kafka のトピック {} のメタデータを取れません。{} 後にやり直します", topics, retryInterval)
            delay(retryInterval)
        }
        isReady = true
    }

    // partitionsFor は、メタデータを取れるまで max.block.ms(既定 60 秒)待つ
    private suspend fun check(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                topics.all { producer.partitionsFor(it).isNotEmpty() }
            } catch (_: KafkaException) {
                false
            }
        }

    private companion object {
        val DEFAULT_RETRY_INTERVAL = 5.seconds
        val logger = LoggerFactory.getLogger(KafkaTopics::class.java)
    }
}
