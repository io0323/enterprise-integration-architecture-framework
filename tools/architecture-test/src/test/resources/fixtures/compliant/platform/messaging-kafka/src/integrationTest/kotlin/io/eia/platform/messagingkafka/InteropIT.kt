package io.eia.platform.messagingkafka

// 適合: Apicurio の公式の Serde はテストのソースセットで使ってよい(相互運用の検査)
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer

class InteropIT {
    val deserializer = AvroKafkaDeserializer<Any>()
}
