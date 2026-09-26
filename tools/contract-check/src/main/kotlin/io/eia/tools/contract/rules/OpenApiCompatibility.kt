package io.eia.tools.contract.rules

import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText

/**
 * OpenAPI の破壊的変更検知。oasdiff(`oasdiff breaking --format json`)を外部プロセスで実行する(ADR-0013)。
 * 比較元は同じ相対パスの OpenAPI。比較元にないファイルは新規として検査しない。
 */
class OpenApiCompatibility(
    private val oasdiff: Path?,
) {
    fun check(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> {
        val previous = baseline.openApis.associateBy { it.path }
        val pairs = current.openApis.mapNotNull { document -> previous[document.path]?.let { it.file to document } }
        return when {
            pairs.isEmpty() -> {
                emptyList()
            }

            oasdiff == null || !oasdiff.isExecutable() -> {
                listOf(Violation(pairs.first().second.path, Rule.TOOL_FAILURE, "oasdiff が見つかりません: ${oasdiff ?: "(未指定)"}"))
            }

            else -> {
                pairs.flatMap { (base, document) -> breaking(oasdiff, base, document.file, document.path) }
            }
        }
    }

    private fun breaking(
        executable: Path,
        base: Path,
        revision: Path,
        path: String,
    ): List<Violation> =
        try {
            val output = run(executable, base, revision)
            if (output.exitCode == 0) {
                parseChanges(output.stdout, path)
            } else {
                val stderr = output.stderr.take(MAX_STDERR)
                listOf(Violation(path, Rule.TOOL_FAILURE, "oasdiff が失敗しました(exit ${output.exitCode}): $stderr"))
            }
        } catch (e: IOException) {
            listOf(Violation(path, Rule.TOOL_FAILURE, "oasdiff を実行できません: ${e.message}"))
        }

    /** `--format json` の出力のうち、level が ERR(破壊的変更)のものを違反にする。 */
    private fun parseChanges(
        stdout: String,
        path: String,
    ): List<Violation> =
        try {
            json.readTree(stdout).values().filter { it.get("level")?.asInt() == LEVEL_ERROR }.map { change ->
                val operation = listOfNotNull(change.get("operation")?.asString(), change.get("path")?.asString()).joinToString(" ")
                Violation(path, Rule.COMPAT_OPENAPI, "$operation: ${change.get("text")?.asString()} [${change.get("id")?.asString()}]")
            }
        } catch (e: JacksonException) {
            listOf(Violation(path, Rule.TOOL_FAILURE, "oasdiff の出力を解釈できません: ${e.originalMessage}"))
        }

    private class Output(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        executable: Path,
        base: Path,
        revision: Path,
    ): Output {
        // stderr はファイルに逃がし、stdout と stderr のパイプが両方埋まって止まることを避ける
        val stderrFile = Files.createTempFile("oasdiff", ".stderr")
        try {
            val process =
                ProcessBuilder(executable.toString(), "breaking", base.toString(), revision.toString(), "--format", "json")
                    .redirectError(stderrFile.toFile())
                    .start()
            val stdout = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("タイムアウト(${TIMEOUT_SECONDS}s)")
            }
            return Output(process.exitValue(), stdout, stderrFile.readText())
        } finally {
            stderrFile.deleteIfExists()
        }
    }

    private companion object {
        /** oasdiff の level: 1 = INFO, 2 = WARN, 3 = ERR。破壊的変更は ERR。 */
        const val LEVEL_ERROR = 3
        const val TIMEOUT_SECONDS = 60L
        const val MAX_STDERR = 500
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
