package io.eia.tools.contract

// 適合: tools は Avro(互換性の検査)を使ってよい
import org.apache.avro.SchemaCompatibility

object AvroCompat {
    val type = SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE
}
