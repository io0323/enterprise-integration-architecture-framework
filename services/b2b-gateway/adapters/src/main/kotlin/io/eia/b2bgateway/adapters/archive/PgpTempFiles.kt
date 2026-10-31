package io.eia.b2bgateway.adapters.archive

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * PGP の部品(platform/file-transfer の PgpFiles)はファイルを読み書きするので、[workDir] の一時ファイルで受け渡す。
 * 一時ファイルは、成功しても失敗しても消す(復号した交換をディスクに残さない)。
 */
internal inline fun <T> withTempFiles(
    workDir: Path,
    input: ByteArray,
    block: (source: Path, target: Path) -> T,
): T {
    val source = Files.createTempFile(workDir, "b2b-in", ".bin")
    val target = Files.createTempFile(workDir, "b2b-out", ".bin")
    try {
        Files.write(source, input)
        return block(source, target)
    } finally {
        source.deleteIfExists()
        target.deleteIfExists()
    }
}
