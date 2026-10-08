package io.eia.legacyorderacl.adapters.reconcile

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/** 照合で出力のトピックを読む Consumer の設定。Consumer Group に参加しない(assign で読み、オフセットをコミットしない)。 */
public object PublishedReaderSettings {
    private const val MAX_POLL_RECORDS = 500

    public fun properties(bootstrapServers: String): Map<String, Any> =
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.CLIENT_ID_CONFIG to "legacy-order-acl-reconcile",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to MAX_POLL_RECORDS,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        )
}
