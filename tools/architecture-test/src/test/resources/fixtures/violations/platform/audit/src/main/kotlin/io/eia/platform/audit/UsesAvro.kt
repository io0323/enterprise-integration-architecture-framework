package io.eia.platform.audit

// 違反: Avro を platform/messaging-kafka・services・tools の外で使う(ADR-0025 §5)
import org.apache.avro.Schema

class UsesAvro {
    fun parse(json: String): Schema = Schema.Parser().parse(json)
}
