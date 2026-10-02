package io.eia.tools.architecture

/** platform のモジュール間の依存 1 本。[source] は根拠(import したファイルか build.gradle.kts)のルートからの相対パス。 */
internal data class PlatformDependency(
    val from: String,
    val to: String,
    val source: String,
    val detail: String,
)

/**
 * platform のモジュール間の依存を、許可した一覧([ALLOWED])に限る(MODULE_DESIGN §2)。
 *
 * 依存は次の 2 つから集める。どちらも本番の依存だけを見る(テストのソースセットと `testImplementation` などは除く)。
 * - テスト以外のソースセットの import と、完全修飾名での参照(`io.eia.platform.<module>`)
 * - `platform/<module>/build.gradle.kts` の `api` / `implementation` / `compileOnly` / `runtimeOnly` の `project(":platform:<module>")`
 *   (1 行で書いた宣言だけを見る。型安全アクセサ `projects.platform.x` はこのリポジトリでは使わない前提。
 *   見逃した宣言も、コードで使えば import の検査で見つかる)
 *
 * platform/test-support は対象外にする(テストのソースセットからだけ参照する規則は [ArchitectureRules.testSupportOnlyFromTests])。
 */
internal object PlatformDependencyRules {
    /**
     * 許可する依存(依存元 → 依存先)。ここにない依存は違反にする。追加するときは MODULE_DESIGN §2 の一覧も更新する。
     * - audit → security: S3 の資格情報を SecretProvider から取る(ADR-0008・ADR-0019 §6)
     * - audit → observability: details の値のマスキング(ADR-0018 §3)
     * - security → reliability: トークン取得の Retry と Circuit Breaker、Retry-After の解析(ADR-0019 §4・ADR-0021)
     * - security → api: 401 / 403 / 503 を Problem Details で返す(ADR-0019 §5・ADR-0022 §2)
     * - api → observability: Problem Details の correlationId(ADR-0022 §2)
     * - messaging-kafka → schema-registry: 書き込みのスキーマ ID(起動時に解決したもの)と受信時の書き手のスキーマ(ADR-0025 §3)
     * - messaging-kafka → observability: PRODUCER の span と Correlation ID(ADR-0025 §4・ADR-0018 §2)
     */
    val ALLOWED: Map<String, Set<String>> =
        mapOf(
            "api" to setOf("observability"),
            "audit" to setOf("security", "observability"),
            "messaging-kafka" to setOf("schema-registry", "observability"),
            "security" to setOf("reliability", "api"),
        )

    private const val PLATFORM_PACKAGE = "io.eia.platform"
    private const val TEST_SUPPORT = "test-support"
    private const val RULE = "platform 間の依存"
    private const val CYCLE_RULE = "platform 間の循環"

    private val TEST_SOURCE_SET = Regex("""/src/(test|integrationTest|e2eTest|[A-Za-z0-9]+Test)/kotlin/""")
    private val IMPORT_OR_PACKAGE_LINE = Regex("""^\s*(import|package)\s.*$""", RegexOption.MULTILINE)
    private val MAIN_PROJECT_DEPENDENCY =
        Regex("""^\s*(api|implementation|compileOnly|compileOnlyApi|runtimeOnly)\s*\(\s*project\(\s*":platform:([\w-]+)"""")

    /** 許可されていない依存。 */
    fun unexpectedDependencies(codeBase: CodeBase): List<Violation> =
        dependencies(codeBase)
            .filter { it.to !in ALLOWED[it.from].orEmpty() }
            .map { Violation(RULE, it.source, "${it.from} → ${it.to}(${it.detail})。許可: ${allowedText(it.from)}") }

    /** 実際の依存の循環。循環ごとに 1 件(依存元の名前で最小のモジュールから数える)。 */
    fun cycles(codeBase: CodeBase): List<Violation> {
        val found = dependencies(codeBase)
        val graph = found.groupBy({ it.from }, { it.to }).mapValues { it.value.toSet() }
        return findCycles(graph).map { cycle ->
            val source = found.first { it.from == cycle[0] && it.to == cycle[1] }.source
            Violation(CYCLE_RULE, source, (cycle + cycle[0]).joinToString(" → "))
        }
    }

    /** 本番の依存を集める(同じ依存元・依存先・根拠の重複は 1 件にする)。 */
    fun dependencies(codeBase: CodeBase): List<PlatformDependency> {
        val modules = codeBase.platformModules.filter { it != TEST_SUPPORT }
        val packages = modules.associateBy { "$PLATFORM_PACKAGE.${it.replace("-", "")}" }
        val fromSources =
            codeBase
                .filesUnder("platform/")
                .filter { !TEST_SOURCE_SET.containsMatchIn(it.path) }
                .flatMap { file ->
                    val from = file.path.split('/')[1]
                    val code = file.code.replace(IMPORT_OR_PACKAGE_LINE, "")
                    packages.mapNotNull { (packageName, to) ->
                        when {
                            to == from || from == TEST_SUPPORT -> {
                                null
                            }

                            file.importNames.any { it == packageName || it.startsWith("$packageName.") } -> {
                                PlatformDependency(from, to, file.path, "import $packageName")
                            }

                            Regex("""(?<![\w.])${Regex.escape(packageName)}\.""").containsMatchIn(code) -> {
                                PlatformDependency(from, to, file.path, "完全修飾名 $packageName")
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
        val fromBuildScripts =
            codeBase.platformBuildScripts.flatMap { (from, script) ->
                script
                    .lineSequence()
                    .mapNotNull { MAIN_PROJECT_DEPENDENCY.find(it) }
                    .map { it.groupValues[1] to it.groupValues[2] }
                    .filter { (_, to) -> to != from && to != TEST_SUPPORT && from != TEST_SUPPORT }
                    .map { (configuration, to) ->
                        PlatformDependency(from, to, "platform/$from/build.gradle.kts", "$configuration(project(\":platform:$to\"))")
                    }.toList()
            }
        return (fromSources + fromBuildScripts).distinct()
    }

    /**
     * 有向グラフの循環を列挙する。各循環は、含まれるモジュールで名前が最小のものから始まる並びで 1 回だけ返す。
     * モジュールの数は十数個なので、単純な深さ優先探索で足りる。
     */
    fun findCycles(graph: Map<String, Set<String>>): List<List<String>> {
        val cycles = linkedSetOf<List<String>>()

        fun visit(
            node: String,
            path: List<String>,
        ) {
            graph[node].orEmpty().sorted().forEach { next ->
                val index = path.indexOf(next)
                when {
                    index >= 0 -> cycles += path.subList(index, path.size).normalized()
                    else -> visit(next, path + next)
                }
            }
        }

        graph.keys.sorted().forEach { visit(it, listOf(it)) }
        return cycles.toList()
    }

    /** 名前が最小のモジュールから始まるように回転する。 */
    private fun List<String>.normalized(): List<String> {
        val start = indexOf(min())
        return drop(start) + take(start)
    }

    private fun allowedText(from: String): String = ALLOWED[from]?.sorted()?.joinToString().takeUnless { it.isNullOrEmpty() } ?: "なし"
}
