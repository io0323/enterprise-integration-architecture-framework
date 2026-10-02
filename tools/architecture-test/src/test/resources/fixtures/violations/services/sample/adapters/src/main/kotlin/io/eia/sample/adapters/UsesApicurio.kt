package io.eia.sample.adapters

// 違反: Apicurio の公式の Serde を本番コードで使う(自前の wire format を使う。ADR-0025 §1)
import io.apicurio.registry.serde.avro.AvroKafkaSerializer

class UsesApicurio {
    val serializer = AvroKafkaSerializer<Any>()
}
