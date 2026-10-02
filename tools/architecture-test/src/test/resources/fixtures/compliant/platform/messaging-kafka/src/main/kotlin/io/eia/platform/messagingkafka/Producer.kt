package io.eia.platform.messagingkafka

// 適合: platform/messaging-kafka は Kafka と Avro を使う
import com.github.avrokotlin.avro4k.Avro
import org.apache.kafka.clients.producer.Producer as KafkaProducer

class Producer(
    val producer: KafkaProducer<ByteArray, ByteArray>,
    val avro: Avro,
)
