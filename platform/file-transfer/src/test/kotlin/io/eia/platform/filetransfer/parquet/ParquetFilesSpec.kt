package io.eia.platform.filetransfer.parquet

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ParquetFilesSpec :
    FunSpec({
        val workDir = Files.createTempDirectory("parquet-files-spec")

        afterSpec { workDir.toFile().deleteRecursively() }

        fun footerOf(bytes: ByteArray): FooterResult =
            ParquetFiles.readFooter(Files.createTempFile(workDir, "f", ".parquet").also { Files.write(it, bytes) })

        fun tail(
            footerLength: Int,
            magic: String = "PAR1",
        ): ByteArray =
            ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(footerLength)
                .put(magic.toByteArray())
                .array()

        test("壊れたファイルは、固定の理由で読めないと返す(大きなフッタの長さで領域を確保しない)") {
            footerOf(ByteArray(5)).shouldBeInstanceOf<FooterResult.Unreadable>().reason shouldBe "Parquet として短すぎます"
            footerOf("PAR1".toByteArray() + ByteArray(20) + tail(4, "XXXX"))
                .shouldBeInstanceOf<FooterResult.Unreadable>()
                .reason shouldBe "末尾が PAR1 ではありません"
            footerOf("PAR1".toByteArray() + ByteArray(20) + tail(Int.MAX_VALUE))
                .shouldBeInstanceOf<FooterResult.Unreadable>()
                .reason shouldBe "フッタの長さが範囲外です"
            footerOf("PAR1".toByteArray() + ByteArray(20) + tail(-1)).shouldBeInstanceOf<FooterResult.Unreadable>()
            // 長さは範囲内だが、中身が Thrift として読めない
            footerOf("PAR1".toByteArray() + ByteArray(20) { 0x7f } + tail(16))
                .shouldBeInstanceOf<FooterResult.Unreadable>()
                .reason shouldBe "フッタを読めません"
        }
    })
