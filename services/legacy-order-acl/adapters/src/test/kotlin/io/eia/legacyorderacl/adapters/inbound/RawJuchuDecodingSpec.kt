package io.eia.legacyorderacl.adapters.inbound

import com.github.avrokotlin.avro4k.Avro
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream

/**
 * avro4k で Debezium の Envelope を読めることを、Converter が実際に登録したスキーマ(`debezium/t_juchu-envelope.avsc`。
 * ローカル基盤の Apicurio から取得した、参照なしの 1 つのスキーマ)で確かめる(ADR-0026 §4)。
 * 書き手のスキーマで GenericRecord を書き、avro4k の型で読む。
 */
class RawJuchuDecodingSpec :
    FunSpec({
        val schema = Schema.Parser().parse(javaClass.getResourceAsStream("/debezium/t_juchu-envelope.avsc"))

        fun row(number: String): GenericData.Record =
            GenericData
                .Record(
                    schema
                        .getField("before")
                        .schema()
                        .types
                        .first { it.type == Schema.Type.RECORD },
                ).apply {
                    put("col_01", "5")
                    put("col_02", number)
                    put("col_03", "1")
                    put("col_04", "山田商事株式会社".padEnd(40))
                    put("col_05", "C0000101")
                    put("col_06", "1200.00")
                    put("col_07", 1_791_454_000_123_456L)
                    put("col_08", 253_402_214_400_000_000L)
                }

        fun encode(record: GenericData.Record): ByteArray =
            ByteArrayOutputStream().use { out ->
                val encoder = EncoderFactory.get().binaryEncoder(out, null)
                GenericDatumWriter<GenericData.Record>(schema).write(record, encoder)
                encoder.flush()
                out.toByteArray()
            }

        fun envelope(
            op: String,
            before: GenericData.Record?,
            after: GenericData.Record?,
            snapshot: String?,
        ): GenericData.Record {
            val sourceSchema = schema.getField("source").schema()
            val source =
                GenericData.Record(sourceSchema).apply {
                    put("version", "3.6.3.Final")
                    put("connector", "postgresql")
                    put("name", "_cdc.legacy")
                    put("ts_ms", 1_791_454_000_123L)
                    put("snapshot", snapshot)
                    put("db", "legacy_sim")
                    put("schema", "public")
                    put("table", "t_juchu")
                    put("lsn", 26_000_000L)
                    put("ts_us", 1_791_454_000_123_456L)
                }
            return GenericData.Record(schema).apply {
                put("before", before)
                put("after", after)
                put("source", source)
                put("op", op)
                put("ts_ms", 1_791_454_000_200L)
            }
        }

        test("登録の変更(op=c)を、Debezium の名前の @SerialName の型で読める。使わない項目は読み飛ばす") {
            val decoded =
                Avro.decodeFromByteArray(
                    schema,
                    RawJuchuEnvelope.serializer(),
                    encode(envelope("c", null, row("J000000001"), "false")),
                )
            decoded.op shouldBe "c"
            decoded.before.shouldBeNull()
            val after = requireNotNull(decoded.after)
            after.orderNumber shouldBe "J000000001"
            after.customerName.length shouldBe 40
            after.amount shouldBe "1200.00"
            after.orderedAtLocalMicros shouldBe 1_791_454_000_123_456L
            decoded.source.lsn shouldBe 26_000_000L
            decoded.source.snapshot shouldBe "false"
            decoded.source.committedAtMicros shouldBe 1_791_454_000_123_456L
        }

        test("削除の変更(op=d)は before だけを持ち、Incremental Snapshot の印と null の snapshot も読める") {
            val deleted =
                Avro.decodeFromByteArray(
                    schema,
                    RawJuchuEnvelope.serializer(),
                    encode(envelope("d", row("J000000002"), null, null)),
                )
            deleted.after.shouldBeNull()
            deleted.before?.orderNumber shouldBe "J000000002"
            deleted.source.snapshot.shouldBeNull()
            val incremental =
                Avro.decodeFromByteArray(
                    schema,
                    RawJuchuEnvelope.serializer(),
                    encode(envelope("r", null, row("J000000003"), "incremental")),
                )
            incremental.source.snapshot shouldBe "incremental"
        }
    })
