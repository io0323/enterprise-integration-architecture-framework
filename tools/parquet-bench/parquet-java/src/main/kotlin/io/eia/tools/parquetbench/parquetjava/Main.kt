package io.eia.tools.parquetbench.parquetjava

import io.eia.tools.parquetbench.Attempt
import io.eia.tools.parquetbench.Bench
import io.eia.tools.parquetbench.SalesRow
import io.eia.tools.parquetbench.attempt
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.conf.ParquetConfiguration
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.api.WriteSupport
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.io.OutputFile
import org.apache.parquet.io.api.Binary
import org.apache.parquet.io.api.RecordConsumer
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Types

/**
 * parquet-java の低水準の API(WriteSupport)で書く。Hadoop がクラスパスにあれば Hadoop の Configuration と既定の CodecFactory で、
 * なければ PlainParquetConfiguration と [ZstdCodecFactory] で書く(parquet-java と parquet-java-hadoop は同じコードで、クラスパスだけが違う)。
 */
fun main(args: Array<String>) {
    val hadoop = attempt { Class.forName("org.apache.hadoop.conf.Configuration") } is Attempt.Ok
    val footerReaders =
        mapOf(
            "parquetFileReader" to ::readRowCountWithParquetFileReader,
            "thriftFooter" to ::readRowCountFromThriftFooter,
        )
    Bench.run(if (hadoop) "parquet-java-hadoop" else "parquet-java", args, footerReaders) { rows, target ->
        val builder =
            SalesWriterBuilder(LocalOutputFile(target))
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
        // Hadoop があれば従来の使い方(Hadoop の Configuration と既定の CodecFactory)。なければ Hadoop に触れない設定と圧縮の factory
        val configured =
            if (hadoop) {
                builder.withConf(Configuration())
            } else {
                builder.withConf(PlainParquetConfiguration()).withCodecFactory(ZstdCodecFactory())
            }
        configured.build().use { writer -> rows.forEach(writer::write) }
    }
}

val SALES_SCHEMA: MessageType =
    Types
        .buildMessage()
        .required(PrimitiveTypeName.INT32)
        .`as`(LogicalTypeAnnotation.dateType())
        .named("business_date")
        .required(PrimitiveTypeName.BINARY)
        .`as`(LogicalTypeAnnotation.stringType())
        .named("order_id")
        .required(PrimitiveTypeName.INT64)
        .`as`(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS))
        .named("ordered_at")
        .required(PrimitiveTypeName.BINARY)
        .`as`(LogicalTypeAnnotation.stringType())
        .named("status")
        .required(PrimitiveTypeName.INT64)
        .named("total_minor_units")
        .required(PrimitiveTypeName.BINARY)
        .`as`(LogicalTypeAnnotation.stringType())
        .named("currency")
        .required(PrimitiveTypeName.BINARY)
        .`as`(LogicalTypeAnnotation.stringType())
        .named("ship_country")
        .optional(PrimitiveTypeName.BINARY)
        .`as`(LogicalTypeAnnotation.stringType())
        .named("ship_region")
        .named("sales_daily")

private class SalesWriterBuilder(
    file: OutputFile,
) : ParquetWriter.Builder<SalesRow, SalesWriterBuilder>(file) {
    override fun self(): SalesWriterBuilder = this

    // Hadoop の型を引数に持つ抽象メソッド。コンパイルには Hadoop の API が要る(実行時は、呼ばれないので要らない)
    override fun getWriteSupport(conf: Configuration): WriteSupport<SalesRow> = SalesWriteSupport()

    override fun getWriteSupport(conf: ParquetConfiguration): WriteSupport<SalesRow> = SalesWriteSupport()
}

private class SalesWriteSupport : WriteSupport<SalesRow>() {
    private lateinit var consumer: RecordConsumer

    override fun init(configuration: Configuration): WriteContext = WriteContext(SALES_SCHEMA, emptyMap())

    override fun init(configuration: ParquetConfiguration): WriteContext = WriteContext(SALES_SCHEMA, emptyMap())

    override fun prepareForWrite(recordConsumer: RecordConsumer) {
        consumer = recordConsumer
    }

    override fun write(record: SalesRow) {
        consumer.startMessage()
        field(0, "business_date") { consumer.addInteger(record.businessDateEpochDay) }
        field(1, "order_id") { consumer.addBinary(Binary.fromString(record.orderId)) }
        field(2, "ordered_at") { consumer.addLong(record.orderedAtMicros) }
        field(3, "status") { consumer.addBinary(Binary.fromString(record.status)) }
        field(4, "total_minor_units") { consumer.addLong(record.totalMinorUnits) }
        field(5, "currency") { consumer.addBinary(Binary.fromString(record.currency)) }
        field(6, "ship_country") { consumer.addBinary(Binary.fromString(record.shipCountry)) }
        record.shipRegion?.let { region -> field(7, "ship_region") { consumer.addBinary(Binary.fromString(region)) } }
        consumer.endMessage()
    }

    private inline fun field(
        index: Int,
        name: String,
        value: () -> Unit,
    ) {
        consumer.startField(name, index)
        value()
        consumer.endField(name, index)
    }
}
