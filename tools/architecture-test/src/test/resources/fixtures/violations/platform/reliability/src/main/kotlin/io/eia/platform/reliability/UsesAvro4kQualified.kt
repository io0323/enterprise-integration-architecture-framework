package io.eia.platform.reliability

// 違反: avro4k を完全修飾名で platform/messaging-kafka の外から使う(ADR-0025 §5)
class UsesAvro4kQualified {
    val avro = com.github.avrokotlin.avro4k.Avro
}
