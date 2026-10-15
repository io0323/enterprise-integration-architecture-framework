package io.eia.tools.parquetbench.verify

import io.eia.tools.parquetbench.Attempt
import io.eia.tools.parquetbench.Bench
import io.eia.tools.parquetbench.Digest
import io.eia.tools.parquetbench.SalesRow
import io.eia.tools.parquetbench.SalesRows
import io.eia.tools.parquetbench.attempt
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.io.LocalInputFile
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 書いたファイルを、別の 2 つの実装(parquet-java と DuckDB)で読み直す。引数は `<ディレクトリ>`。ファイルごとに 1 行の JSON を出す。
 * - 物理型・論理型・圧縮・行グループの数(parquet-java でフッタを読む)
 * - 内容の要約(件数・金額の合計・null の数・時刻の最大・order_id の順と値の MD5)が、生成した行と一致するか
 */
fun main(args: Array<String>) {
    val files = Path(args.single()).listDirectoryEntries("*.parquet").sortedBy { it.name }
    DriverManager.getConnection("jdbc:duckdb:").use { duckdb ->
        for (file in files) {
            val footer =
                when (val read = attempt { footer(file) }) {
                    is Attempt.Ok -> read.value
                    is Attempt.Failed -> {
                        println(Bench.json(mapOf("file" to file.name, "footerError" to read.error)))
                        continue
                    }
                }
            val expected = SalesRows.digest(SalesRows.generate((footer["rows"] as Long).toInt()))
            val byParquetJava = attempt { SalesRows.digest(readWithParquetJava(file)) }
            val byDuckDb =
                attempt {
                    duckdb.prepareStatement(DUCKDB_DIGEST).use { statement ->
                        statement.setString(1, file.toString())
                        statement.executeQuery().use { result ->
                            result.next()
                            Digest(result.getLong(1), result.getLong(2), result.getLong(3), result.getLong(4), result.getString(5))
                        }
                    }
                }
            println(
                Bench.json(
                    mapOf("file" to file.name) + footer +
                        mapOf(
                            "parquetJavaMatches" to (byParquetJava.valueOrNull() == expected),
                            "parquetJavaError" to byParquetJava.errorOrNull(),
                            "duckdbMatches" to (byDuckDb.valueOrNull() == expected),
                            "duckdbError" to byDuckDb.errorOrNull(),
                        ),
                ),
            )
        }
    }
}

private val DUCKDB_DIGEST =
    """
    SELECT count(*), sum(total_minor_units), count(*) - count(ship_region), epoch_us(max(ordered_at)),
           md5(string_agg(order_id || chr(10), '' ORDER BY file_row_number))
    FROM read_parquet(?, file_row_number = true)
    """.trimIndent()

private fun footer(file: Path): Map<String, Any?> =
    ParquetFileReader.open(LocalInputFile(file)).use { reader ->
        val metadata = reader.footer
        val schema = metadata.fileMetaData.schema
        mapOf(
            "rows" to reader.recordCount,
            "rowGroups" to metadata.blocks.size,
            "codecs" to
                metadata.blocks
                    .flatMap { block -> block.columns.map { it.codec.name } }
                    .distinct(),
            "createdBy" to metadata.fileMetaData.createdBy,
            "columns" to
                schema.columns.map { column ->
                    val primitive = column.primitiveType
                    "${primitive.name} ${primitive.repetition.name.lowercase()} ${primitive.primitiveTypeName}" +
                        (primitive.logicalTypeAnnotation?.let { " $it" } ?: "")
                },
        )
    }

private fun readWithParquetJava(file: Path): Sequence<SalesRow> =
    sequence {
        ParquetFileReader.open(LocalInputFile(file)).use { reader ->
            val schema = reader.footer.fileMetaData.schema
            val io = ColumnIOFactory().getColumnIO(schema)
            while (true) {
                val rowGroup = reader.readNextRowGroup() ?: break
                val records = io.getRecordReader(rowGroup, GroupRecordConverter(schema))
                repeat(rowGroup.rowCount.toInt()) { yield(toRow(records.read())) }
            }
        }
    }

private fun toRow(group: Group): SalesRow =
    SalesRow(
        businessDateEpochDay = group.getInteger("business_date", 0),
        orderId = group.getString("order_id", 0),
        orderedAtMicros = group.getLong("ordered_at", 0),
        status = group.getString("status", 0),
        totalMinorUnits = group.getLong("total_minor_units", 0),
        currency = group.getString("currency", 0),
        shipCountry = group.getString("ship_country", 0),
        shipRegion = if (group.getFieldRepetitionCount("ship_region") == 0) null else group.getString("ship_region", 0),
    )
