package io.eia.tools.parquetbench.parquetjava

import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.format.Util
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.LocalInputFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path

/** parquet-java の通常の読み手(ParquetFileReader)で件数を読む。Hadoop に触れない設定と圧縮の factory を渡す。 */
fun readRowCountWithParquetFileReader(file: Path): Long {
    val options =
        ParquetReadOptions
            .builder(PlainParquetConfiguration())
            .withCodecFactory(ZstdCodecFactory())
            .build()
    return ParquetFileReader.open(LocalInputFile(file), options).use { it.recordCount }
}

/**
 * フッタ(Thrift の FileMetaData)を parquet-format-structures で直接読み、件数を返す。
 * ファイルの末尾は「フッタ + フッタの長さ(4 バイト、リトルエンディアン)+ "PAR1"」。
 */
fun readRowCountFromThriftFooter(file: Path): Long =
    FileChannel.open(file).use { channel ->
        val size = channel.size()
        val tail = ByteBuffer.allocate(TAIL_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        channel.read(tail, size - TAIL_BYTES)
        tail.flip()
        val footerLength = tail.int
        val magic = ByteArray(MAGIC.size).also { tail.get(it) }
        require(magic.contentEquals(MAGIC)) { "Parquet のファイルではありません(末尾が PAR1 でない)" }
        val footer = ByteBuffer.allocate(footerLength)
        channel.read(footer, size - TAIL_BYTES - footerLength)
        Util.readFileMetaData(footer.array().inputStream()).num_rows
    }

private val MAGIC = "PAR1".toByteArray()
private const val TAIL_BYTES = 8
