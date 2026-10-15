package io.eia.tools.parquetbench.parquetjava

import com.github.luben.zstd.Zstd
import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.compression.CompressionCodecFactory
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import java.nio.ByteBuffer

/**
 * zstd だけを扱う圧縮の factory(zstd-jni を直接呼ぶ)。parquet-java の既定の CodecFactory は Hadoop の zlib のクラスを参照するので、
 * Hadoop のないクラスパスでは読み込めない(NoClassDefFoundError)。これを渡すと、ParquetWriter.Builder は CodecFactory を作らない。
 */
class ZstdCodecFactory(
    private val level: Int = DEFAULT_LEVEL,
) : CompressionCodecFactory {
    override fun getCompressor(codecName: CompressionCodecName): CompressionCodecFactory.BytesInputCompressor {
        require(codecName == CompressionCodecName.ZSTD) { "zstd だけを扱います: $codecName" }
        return object : CompressionCodecFactory.BytesInputCompressor {
            override fun compress(bytes: BytesInput): BytesInput = BytesInput.from(Zstd.compress(bytes.toByteArray(), level))

            override fun getCodecName(): CompressionCodecName = CompressionCodecName.ZSTD

            override fun release() = Unit
        }
    }

    override fun getDecompressor(codecName: CompressionCodecName): CompressionCodecFactory.BytesInputDecompressor {
        require(codecName == CompressionCodecName.ZSTD) { "zstd だけを扱います: $codecName" }
        return object : CompressionCodecFactory.BytesInputDecompressor {
            override fun decompress(
                bytes: BytesInput,
                uncompressedSize: Int,
            ): BytesInput = BytesInput.from(Zstd.decompress(bytes.toByteArray(), uncompressedSize))

            override fun decompress(
                input: ByteBuffer,
                compressedSize: Int,
                output: ByteBuffer,
                uncompressedSize: Int,
            ) {
                val source = ByteArray(compressedSize).also { input.get(it) }
                output.put(Zstd.decompress(source, uncompressedSize))
            }

            override fun release() = Unit
        }
    }

    override fun release() = Unit

    companion object {
        /** parquet-java の zstd の既定の水準(parquet.compression.codec.zstd.level)と同じ。 */
        const val DEFAULT_LEVEL = 3
    }
}
