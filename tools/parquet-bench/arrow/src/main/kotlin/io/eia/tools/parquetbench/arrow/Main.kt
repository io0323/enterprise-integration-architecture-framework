package io.eia.tools.parquetbench.arrow

import io.eia.tools.parquetbench.Bench
import io.eia.tools.parquetbench.SalesRow
import org.apache.arrow.dataset.file.DatasetFileWriter
import org.apache.arrow.dataset.file.FileFormat
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.listDirectoryEntries

/**
 * Apache Arrow の dataset(JNI で Arrow C++ の Parquet の書き手)で書く。行を 65,536 行ずつの列のバッチにして渡す。
 * Java の API(DatasetFileWriter)は、出力をディレクトリで受け取り、Parquet の書き出しの設定(圧縮など)を渡す引数がない。
 */
fun main(args: Array<String>) {
    Bench.run("arrow", args) { rows, target ->
        val dir = target.resolveSibling("${target.fileName}.d")
        dir.toFile().deleteRecursively()
        RootAllocator().use { allocator ->
            SalesReader(allocator, rows.iterator()).use { reader ->
                DatasetFileWriter.write(allocator, reader, FileFormat.PARQUET, dir.toUri().toString(), emptyArray(), MAX_PARTITIONS, "part-{i}")
            }
        }
        // 出力は 1 ファイル(パーティションなし)。契約のファイル名に移す
        val written = dir.listDirectoryEntries().single()
        Files.move(written, target, StandardCopyOption.REPLACE_EXISTING)
        Files.delete(dir)
    }
}

private const val BATCH_ROWS = 65_536
private const val MAX_PARTITIONS = 1

private val SALES_SCHEMA =
    Schema(
        listOf(
            Field("business_date", FieldType.notNullable(ArrowType.Date(DateUnit.DAY)), null),
            Field("order_id", FieldType.notNullable(ArrowType.Utf8()), null),
            Field("ordered_at", FieldType.notNullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")), null),
            Field("status", FieldType.notNullable(ArrowType.Utf8()), null),
            Field("total_minor_units", FieldType.notNullable(ArrowType.Int(64, true)), null),
            Field("currency", FieldType.notNullable(ArrowType.Utf8()), null),
            Field("ship_country", FieldType.notNullable(ArrowType.Utf8()), null),
            Field("ship_region", FieldType.nullable(ArrowType.Utf8()), null),
        ),
    )

private class SalesReader(
    allocator: BufferAllocator,
    private val rows: Iterator<SalesRow>,
) : ArrowReader(allocator) {
    private var bytes = 0L

    override fun loadNextBatch(): Boolean {
        if (!rows.hasNext()) return false
        val root = vectorSchemaRoot
        root.allocateNew()
        val businessDate = root.getVector("business_date") as DateDayVector
        val orderId = root.getVector("order_id") as VarCharVector
        val orderedAt = root.getVector("ordered_at") as TimeStampMicroTZVector
        val status = root.getVector("status") as VarCharVector
        val total = root.getVector("total_minor_units") as BigIntVector
        val currency = root.getVector("currency") as VarCharVector
        val shipCountry = root.getVector("ship_country") as VarCharVector
        val shipRegion = root.getVector("ship_region") as VarCharVector
        var count = 0
        while (count < BATCH_ROWS && rows.hasNext()) {
            val row = rows.next()
            businessDate.setSafe(count, row.businessDateEpochDay)
            orderId.setSafe(count, row.orderId.toByteArray())
            orderedAt.setSafe(count, row.orderedAtMicros)
            status.setSafe(count, row.status.toByteArray())
            total.setSafe(count, row.totalMinorUnits)
            currency.setSafe(count, row.currency.toByteArray())
            shipCountry.setSafe(count, row.shipCountry.toByteArray())
            val region = row.shipRegion
            if (region == null) shipRegion.setNull(count) else shipRegion.setSafe(count, region.toByteArray())
            count++
        }
        root.rowCount = count
        bytes += root.fieldVectors.sumOf { it.bufferSize.toLong() }
        return true
    }

    override fun bytesRead(): Long = bytes

    override fun closeReadSource() = Unit

    override fun readSchema(): Schema = SALES_SCHEMA
}
