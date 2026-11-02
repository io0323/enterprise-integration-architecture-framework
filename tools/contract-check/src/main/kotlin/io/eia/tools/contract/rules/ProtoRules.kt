package io.eia.tools.contract.rules

import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.text
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlin.io.path.readText

/**
 * proto の検査(ADR-0042 §2・§3)。規則は buf(`contracts/proto/buf.yaml`)を外部プロセスで実行して検査し、buf が見ない置き場所の規則だけを
 * ここで検査する。
 *
 * - `CC-NAMING-013`: 置き場所と Java のパッケージ。
 * - `CC-STRUCT-006`: `buf lint`(package の版・ディレクトリとの一致などの STANDARD)。proto の構文の誤りもここに入れる。
 * - `CC-COMPAT-006`: `buf breaking`(比較元は同じディレクトリの proto。区分は FILE)。
 */
class ProtoRules(
    private val buf: Path?,
) {
    fun check(contracts: Contracts): List<Violation> = contracts.protoFiles.flatMap { checkPlacement(contracts, it) } + lint(contracts)

    private fun checkPlacement(
        contracts: Contracts,
        path: String,
    ): List<Violation> {
        val location =
            Naming.protoLocation(path)
                ?: return listOf(
                    Violation(path, Rule.NAMING_PROTO, "置き場所が contracts/proto/io/eia/{domain}/v{n}/{name}.proto ではありません"),
                )
        val expected = "${location.domain}.v${location.version}"
        val javaPackage = JAVA_PACKAGE.find(contracts.root.resolve(path).readText())?.groupValues?.get(1)
        return if (javaPackage == "$STUB_PACKAGE.$expected") {
            emptyList()
        } else {
            val actual = javaPackage ?: "なし"
            listOf(Violation(path, Rule.NAMING_PROTO, "java_package が '$STUB_PACKAGE.$expected' ではありません(実際: $actual)"))
        }
    }

    private fun lint(contracts: Contracts): List<Violation> =
        contracts.protoFiles
            .firstOrNull()
            ?.let { first ->
                withBuf(first) { executable ->
                    runBuf(
                        first,
                        Rule.STRUCT_PROTO,
                        contracts.root.resolve(Contracts.PROTO_DIR),
                        listOf(executable.toString(), "lint", "."),
                    )
                }
            }.orEmpty()

    /** 比較元に proto がなければ検査しない(すべて新規。buf は proto のないディレクトリとは比べられない)。 */
    fun checkCompatibility(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> =
        when {
            baseline.protoFiles.isEmpty() -> emptyList()
            current.protoFiles.isEmpty() -> baseline.protoFiles.map(::deleted)
            else -> breaking(current, baseline, baseline.protoFiles.first())
        }

    private fun deleted(path: String) =
        Violation(path, Rule.COMPAT_PROTO, "$DELETED_PREFIX\"${path.removePrefix(Contracts.PROTO_DIR)}\" was deleted.")

    private fun breaking(
        current: Contracts,
        baseline: Contracts,
        first: String,
    ): List<Violation> =
        withBuf(first) { executable ->
            val against = baseline.root.resolve(Contracts.PROTO_DIR).absolutePathString()
            runBuf(
                first,
                Rule.COMPAT_PROTO,
                current.root.resolve(Contracts.PROTO_DIR),
                listOf(executable.toString(), "breaking", ".", "--against", against),
            )
        }

    /** buf を実行できなければ、[path] の `CC-TOOL-001` にする(黙って省略しない)。 */
    private fun withBuf(
        path: String,
        block: (Path) -> List<Violation>,
    ): List<Violation> =
        buf?.takeIf { it.isExecutable() }?.let(block)
            ?: listOf(Violation(path, Rule.TOOL_FAILURE, "buf が見つかりません: ${buf ?: "(未指定)"}"))

    /** 終了コード 0 は問題なし、[EXIT_VIOLATIONS] は `--error-format json` の行が違反、それ以外はツールの失敗(黙って省略しない)。 */
    private fun runBuf(
        path: String,
        rule: Rule,
        protoDirectory: Path,
        command: List<String>,
    ): List<Violation> =
        try {
            val output = runTool(command + listOf("--error-format", "json"), protoDirectory)
            when (output.exitCode) {
                0 -> {
                    emptyList()
                }

                EXIT_VIOLATIONS -> {
                    parse(output.stdout, rule, path)
                }

                else -> {
                    listOf(
                        Violation(path, Rule.TOOL_FAILURE, "buf が失敗しました(exit ${output.exitCode}): ${output.stderr.take(MAX_STDERR)}"),
                    )
                }
            }
        } catch (e: IOException) {
            listOf(Violation(path, Rule.TOOL_FAILURE, "buf を実行できません: ${e.message}"))
        }

    private fun parse(
        stdout: String,
        rule: Rule,
        path: String,
    ): List<Violation> =
        try {
            stdout.lines().filter { it.isNotBlank() }.map { line ->
                val node = json.readTree(line)
                val message = node.text("message").orEmpty()
                val type = node.text("type").orEmpty()
                // ファイルの削除の報告には path がないので、文から取り出す
                val file = node.text("path") ?: DELETED_FILE.find(message)?.groupValues?.get(1)
                Violation(
                    file?.let { Contracts.PROTO_DIR + it } ?: path,
                    if (type == COMPILE_ERROR) Rule.STRUCT_PROTO else rule,
                    "$message [$type]",
                )
            }
        } catch (e: JacksonException) {
            listOf(Violation(path, Rule.TOOL_FAILURE, "buf の出力を解釈できません: ${e.originalMessage}"))
        }

    private companion object {
        /** buf は違反を見つけると 100 で終わる。ツール自身の失敗は 1。 */
        const val EXIT_VIOLATIONS = 100
        const val COMPILE_ERROR = "COMPILE"
        const val MAX_STDERR = 500
        const val STUB_PACKAGE = "io.eia.platform.grpcstubs"
        const val DELETED_PREFIX = "Previously present file "
        val DELETED_FILE = Regex("^$DELETED_PREFIX\"([^\"]+)\" was deleted")
        val JAVA_PACKAGE = Regex("""(?m)^option java_package = "([^"]+)";""")
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
