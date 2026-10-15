package io.eia.tools.architecture

/**
 * 本番の依存の宣言(`build.gradle.kts` の `api` / `implementation` / `compileOnly` / `runtimeOnly`)で、ライブラリを使ってよいモジュールを限る。
 * Konsist の import の検査([ArchitectureRules.awsSdkOnlyInAllowedModules]・[ArchitectureRules.parquetOnlyInFileTransfer])と対にして、
 * コードで使わずに依存だけを持ち込むこと(推移的に配布物に入る)も防ぐ。テストの設定(`testImplementation` など)は対象外。
 * 1 行で書いた宣言だけを見る(このリポジトリの書き方)。
 */
internal object LibraryDeclarationRules {
    private val MAIN_DECLARATION = Regex("""^\s*(api|implementation|compileOnly|compileOnlyApi|runtimeOnly)\s*\((.*)$""")
    private val COMMENT = Regex("""^\s*//""")

    private data class Library(
        val rule: String,
        val markers: List<String>,
        val allowedPaths: List<Regex>,
        val allowed: String,
    )

    private val AWS_SDK =
        Library(
            "AWS SDK の依存の宣言",
            listOf("libs.aws.", "\"software.amazon.awssdk:"),
            listOf(Regex("""^platform/file-transfer/"""), Regex("""^platform/audit/""")),
            "platform/file-transfer と platform/audit(アンカー)だけ。ADR-0032 §6",
        )

    private val PARQUET =
        Library(
            "Parquet・Hadoop の依存の宣言",
            listOf("libs.parquet.", "libs.hadoop.", "\"org.apache.parquet:", "\"org.apache.hadoop:"),
            listOf(Regex("""^platform/file-transfer/"""), Regex("""^tools/parquet-bench/""")),
            "platform/file-transfer と tools/parquet-bench(計測)だけ。ADR-0033 §3",
        )

    private val HADOOP_MARKERS = listOf("libs.hadoop.", "\"org.apache.hadoop:")
    private val HADOOP_RUNTIME_CHECKED = listOf(Regex("""^platform/"""), Regex("""^services/"""))

    fun violations(codeBase: CodeBase): List<Violation> =
        codeBase.buildScripts.flatMap { (path, script) ->
            mainDeclarations(script).flatMap { (configuration, declaration) ->
                listOfNotNull(
                    outsideAllowed(AWS_SDK, path, declaration),
                    outsideAllowed(PARQUET, path, declaration),
                    hadoopAtRuntime(path, configuration, declaration),
                )
            }
        }

    private fun mainDeclarations(script: String): List<Pair<String, String>> =
        script
            .lineSequence()
            .filterNot { COMMENT.containsMatchIn(it) }
            .mapNotNull { MAIN_DECLARATION.find(it) }
            .map { it.groupValues[1] to it.value.trim() }
            .toList()

    private fun outsideAllowed(
        library: Library,
        path: String,
        declaration: String,
    ): Violation? =
        if (library.markers.any { it in declaration } && library.allowedPaths.none { it.containsMatchIn(path) }) {
            Violation(library.rule, path, "$declaration。許可: ${library.allowed}")
        } else {
            null
        }

    /** ADR-0033 §1: Hadoop はコンパイルにだけ使う(compileOnly)。実行時のクラスパスに入れない。 */
    private fun hadoopAtRuntime(
        path: String,
        configuration: String,
        declaration: String,
    ): Violation? =
        if (HADOOP_MARKERS.any { it in declaration } && configuration != "compileOnly" &&
            HADOOP_RUNTIME_CHECKED.any { it.containsMatchIn(path) }
        ) {
            Violation("Hadoop は compileOnly だけ", path, "$declaration。ADR-0033 §1")
        } else {
            null
        }
}
