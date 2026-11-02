package io.eia.tools.contract.rules

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/** 外部の検査ツールの実行結果。 */
internal class ToolOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * 外部の検査ツール(oasdiff・buf)を実行する。起動できない・終わらないときは [IOException]。
 * stderr はファイルに逃がし、stdout と stderr のパイプが両方埋まって止まることを避ける。
 */
internal fun runTool(
    command: List<String>,
    workingDirectory: Path? = null,
): ToolOutput {
    val stderrFile = Files.createTempFile("contract-check-tool", ".stderr")
    try {
        val builder = ProcessBuilder(command).redirectError(stderrFile.toFile())
        workingDirectory?.let { builder.directory(it.toFile()) }
        val process = builder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("タイムアウト(${TIMEOUT_SECONDS}s)")
        }
        return ToolOutput(process.exitValue(), stdout, stderrFile.readText())
    } finally {
        stderrFile.deleteIfExists()
    }
}

private const val TIMEOUT_SECONDS = 60L
