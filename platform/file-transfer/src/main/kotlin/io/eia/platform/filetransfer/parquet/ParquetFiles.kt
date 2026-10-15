package io.eia.platform.filetransfer.parquet

import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileColumn
import io.eia.platform.filetransfer.layout.FileLayout
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.conf.ParquetConfiguration
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.format.FieldRepetitionType
import org.apache.parquet.format.SchemaElement
import org.apache.parquet.format.Type
import org.apache.parquet.format.Util
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
import org.apache.parquet.schema.Type.Repetition
import org.apache.parquet.schema.Types
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Parquet の書き出しとフッタの読み取り(ADR-0033)。実行時に Hadoop を使わない:
 * - 書き出しは `PlainParquetConfiguration` と [ZstdCodecFactory] を渡す(§1)
 * - 読み取りは `ParquetFileReader` を使わず、フッタ(Thrift の FileMetaData)を直接読む(§2)
 */
internal object ParquetFiles {
    /** フッタの大きさの上限。これより大きいフッタは読まない(壊れた・悪意のあるファイルで大きな領域を確保しない)。 */
    private const val MAX_FOOTER_BYTES = 16 * 1024 * 1024
    private const val TAIL_BYTES = 8
    private const val MIN_FILE_BYTES = 12
    private val MAGIC = "PAR1".toByteArray()

    fun schemaOf(layout: FileLayout): MessageType {
        val builder = Types.buildMessage()
        for (column in layout.columns) {
            val repetition = if (column.nullable) Repetition.OPTIONAL else Repetition.REQUIRED
            val primitive = Types.primitive(physicalType(column.type), repetition)
            logicalType(column.type)?.let { primitive.`as`(it) }
            builder.addField(primitive.named(column.name))
        }
        return builder.named(layout.dataset.replace('-', '_'))
    }

    /** [target] に書く行の書き手。行の値は [io.eia.platform.filetransfer.write.RowCheck] で確かめた後のもの。 */
    fun writer(
        layout: FileLayout,
        target: Path,
    ): ParquetWriter<List<Any?>> =
        RowWriterBuilder(LocalOutputFile(target), layout)
            .withConf(PlainParquetConfiguration())
            .withCodecFactory(ZstdCodecFactory())
            .withCompressionCodec(CompressionCodecName.ZSTD)
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            .build()

    /** フッタの要約。読めなければ [FooterResult.Unreadable](理由は固定の説明だけ)。 */
    fun readFooter(file: Path): FooterResult =
        try {
            FileChannel.open(file).use { channel ->
                val size = channel.size()
                if (size < MIN_FILE_BYTES) return FooterResult.Unreadable("Parquet として短すぎます")
                val tail = ByteBuffer.allocate(TAIL_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(tail, size - TAIL_BYTES)
                tail.flip()
                val length = tail.int
                val magic = ByteArray(MAGIC.size).also { tail.get(it) }
                if (!magic.contentEquals(MAGIC)) return FooterResult.Unreadable("末尾が PAR1 ではありません")
                if (length <= 0 || length > MAX_FOOTER_BYTES || length > size - MIN_FILE_BYTES) {
                    return FooterResult.Unreadable("フッタの長さが範囲外です")
                }
                val footer = ByteBuffer.allocate(length)
                channel.read(footer, size - TAIL_BYTES - length)
                val metadata = Util.readFileMetaData(footer.array().inputStream())
                FooterResult.Read(ParquetFooter(metadata.num_rows, leaves(metadata.schema)))
            }
        } catch (_: IOException) {
            FooterResult.Unreadable("フッタを読めません")
        } catch (_: RuntimeException) {
            // Thrift の復号の失敗など(壊れたフッタ)
            FooterResult.Unreadable("フッタを読めません")
        }

    /** 契約の列とフッタの列が一致しない理由(列ごと)。一致すれば空。 */
    fun mismatches(
        layout: FileLayout,
        footer: ParquetFooter,
    ): List<String> {
        if (footer.columns.map { it.name } != layout.columns.map { it.name }) {
            return listOf("列の名前・順序が契約と違います")
        }
        return layout.columns.zip(footer.columns).mapNotNull { (expected, actual) ->
            when {
                actual.physicalType != physicalType(expected.type).name -> "${expected.name} の物理型"
                actual.optional != expected.nullable -> "${expected.name} の nullable"
                !logicalMatches(expected.type, actual) -> "${expected.name} の論理型"
                else -> null
            }
        }
    }

    private fun leaves(schema: List<SchemaElement>): List<FooterColumn> =
        // 先頭は根。入れ子の列(num_children を持つもの)は契約にないので、根の直下の葉だけを見る
        schema.drop(1).map { element ->
            FooterColumn(
                name = element.name,
                physicalType = if (element.isSetType) physicalName(element.type) else "GROUP",
                optional = element.repetition_type == FieldRepetitionType.OPTIONAL,
                logical = logicalOf(element),
            )
        }

    private fun logicalOf(element: SchemaElement): String? {
        val logical = element.logicalType ?: return element.converted_type?.name
        return when {
            logical.isSetSTRING -> {
                "STRING"
            }

            logical.isSetDATE -> {
                "DATE"
            }

            logical.isSetTIMESTAMP -> {
                val timestamp = logical.timestamp
                val unit = if (timestamp.unit.isSetMICROS) "MICROS" else "OTHER"
                "TIMESTAMP($unit,${timestamp.isIsAdjustedToUTC})"
            }

            logical.isSetINTEGER -> {
                "INTEGER(${logical.integer.bitWidth},${logical.integer.isIsSigned})"
            }

            else -> {
                "OTHER"
            }
        }
    }

    private fun logicalMatches(
        type: ColumnType,
        actual: FooterColumn,
    ): Boolean =
        when (type) {
            ColumnType.STRING -> actual.logical == "STRING" || actual.logical == "UTF8"

            // 注釈なしと、符号ありの INTEGER の注釈(DuckDB などが付ける)を認める
            ColumnType.INT32 -> actual.logical == null || actual.logical == "INTEGER(32,true)" || actual.logical == "INT_32"

            ColumnType.INT64 -> actual.logical == null || actual.logical == "INTEGER(64,true)" || actual.logical == "INT_64"

            ColumnType.BOOLEAN -> actual.logical == null

            ColumnType.DATE -> actual.logical == "DATE"

            ColumnType.TIMESTAMP -> actual.logical == "TIMESTAMP(MICROS,true)"
        }

    private fun physicalType(type: ColumnType): PrimitiveTypeName =
        when (type) {
            ColumnType.STRING -> PrimitiveTypeName.BINARY
            ColumnType.INT32, ColumnType.DATE -> PrimitiveTypeName.INT32
            ColumnType.INT64, ColumnType.TIMESTAMP -> PrimitiveTypeName.INT64
            ColumnType.BOOLEAN -> PrimitiveTypeName.BOOLEAN
        }

    private fun logicalType(type: ColumnType): LogicalTypeAnnotation? =
        when (type) {
            ColumnType.STRING -> LogicalTypeAnnotation.stringType()
            ColumnType.DATE -> LogicalTypeAnnotation.dateType()
            ColumnType.TIMESTAMP -> LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS)
            ColumnType.INT32, ColumnType.INT64, ColumnType.BOOLEAN -> null
        }

    /** Thrift の物理型の名前を PrimitiveTypeName の名前にそろえる(BINARY は Thrift では BYTE_ARRAY)。 */
    private fun physicalName(type: Type): String = if (type == Type.BYTE_ARRAY) PrimitiveTypeName.BINARY.name else type.name
}

/** フッタの読み取りの結果。 */
internal sealed interface FooterResult {
    data class Read(
        val footer: ParquetFooter,
    ) : FooterResult

    data class Unreadable(
        val reason: String,
    ) : FooterResult
}

internal data class ParquetFooter(
    val rowCount: Long,
    val columns: List<FooterColumn>,
)

/** @property logical 論理型の要約(例 `STRING`・`TIMESTAMP(MICROS,true)`)。なければ null */
internal data class FooterColumn(
    val name: String,
    val physicalType: String,
    val optional: Boolean,
    val logical: String?,
)

private class RowWriterBuilder(
    file: OutputFile,
    private val layout: FileLayout,
) : ParquetWriter.Builder<List<Any?>, RowWriterBuilder>(file) {
    override fun self(): RowWriterBuilder = this

    // Hadoop の型を引数に持つ抽象メソッド。コンパイルには Hadoop の API が要るが、実行時には呼ばれない(ADR-0033 §1)
    override fun getWriteSupport(conf: Configuration): WriteSupport<List<Any?>> = RowWriteSupport(layout)

    override fun getWriteSupport(conf: ParquetConfiguration): WriteSupport<List<Any?>> = RowWriteSupport(layout)
}

private class RowWriteSupport(
    private val layout: FileLayout,
) : WriteSupport<List<Any?>>() {
    private val schema = ParquetFiles.schemaOf(layout)
    private lateinit var consumer: RecordConsumer

    override fun init(configuration: Configuration): WriteContext = WriteContext(schema, emptyMap())

    override fun init(configuration: ParquetConfiguration): WriteContext = WriteContext(schema, emptyMap())

    override fun prepareForWrite(recordConsumer: RecordConsumer) {
        consumer = recordConsumer
    }

    override fun write(record: List<Any?>) {
        consumer.startMessage()
        layout.columns.forEachIndexed { index, column ->
            val value = record[index]
            if (value != null) {
                consumer.startField(column.name, index)
                add(column, value)
                consumer.endField(column.name, index)
            }
        }
        consumer.endMessage()
    }

    private fun add(
        column: FileColumn,
        value: Any,
    ) {
        when (column.type) {
            ColumnType.STRING -> consumer.addBinary(Binary.fromString(value as String))
            ColumnType.INT32 -> consumer.addInteger(value as Int)
            ColumnType.INT64 -> consumer.addLong(value as Long)
            ColumnType.BOOLEAN -> consumer.addBoolean(value as Boolean)
            ColumnType.DATE -> consumer.addInteger(Math.toIntExact((value as LocalDate).toEpochDay()))
            ColumnType.TIMESTAMP -> consumer.addLong(ChronoUnit.MICROS.between(Instant.EPOCH, value as Instant))
        }
    }
}
