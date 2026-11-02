package io.eia.tools.contract.rules

import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isExecutable

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
        // 外部の契約(contracts/external)も比べる。結果は ContractCheck が警告(CC-EXTERNAL-001)に移す(ADR-0035 §8)
        val previous = (baseline.openApis + baseline.externalOpenApis).associateBy { it.path }
        val pairs =
            (current.openApis + current.externalOpenApis).mapNotNull { document -> previous[document.path]?.let { it.file to document } }
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
            val output = runTool(listOf(executable.toString(), "breaking", base.toString(), revision.toString(), "--format", "json"))
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

    private companion object {
        /** oasdiff の level: 1 = INFO, 2 = WARN, 3 = ERR。破壊的変更は ERR。 */
        const val LEVEL_ERROR = 3
        const val MAX_STDERR = 500
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
