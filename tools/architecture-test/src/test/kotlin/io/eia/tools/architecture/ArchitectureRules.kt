package io.eia.tools.architecture

/** 規約違反 1 件。[rule] は違反した規約、[path] はルートからの相対パス。 */
internal data class Violation(
    val rule: String,
    val path: String,
    val detail: String,
) {
    override fun toString(): String = "[$rule] $path: $detail"
}

internal fun List<Violation>.assertNone() {
    if (isNotEmpty()) {
        throw AssertionError("アーキテクチャ規約違反が $size 件あります:\n" + joinToString("\n") { "  - $it" })
    }
}

/**
 * CLAUDE.md §4・MODULE_DESIGN §2・ADR-0004 のアーキテクチャ規約。
 * レイヤはパッケージ `io.eia.<service>.<layer>` で判定し、配置とパッケージの一致も検査する。
 */
internal object ArchitectureRules {
    private const val BASE_PACKAGE = "io.eia"

    /** 各レイヤが import してよい同一サービス内のレイヤ(Clean Architecture: 外側から内側への依存のみ)。 */
    private val ALLOWED_LAYER_DEPENDENCIES =
        mapOf(
            "domain" to emptySet(),
            "application" to setOf("domain"),
            "adapters" to setOf("application", "domain"),
            "app" to setOf("adapters", "application", "domain"),
        )
    val LAYERS: Set<String> = ALLOWED_LAYER_DEPENDENCIES.keys

    /** ADR-0004 §2: commonMain で禁止する import のパッケージ。 */
    val FORBIDDEN_COMMON_MAIN_PACKAGES =
        listOf(
            "java",
            "javax",
            "kotlin.jvm",
            "io.ktor",
            "org.apache.kafka",
            "org.jetbrains.exposed",
            "org.koin",
            "org.flywaydb",
            "io.opentelemetry",
            "com.github.avrokotlin",
            "io.github.resilience4j",
        )

    /**
     * 禁止リストの例外。
     * - `kotlin.jvm.JvmInline`: CODING_STANDARDS の値オブジェクト(`@JvmInline value class`)に必要。stdlib の common API(ADR-0010)。
     * - shared/integration-sdk の `io.ktor.client`(ADR-0004 §2)。
     */
    private val COMMON_MAIN_ALLOWED_IMPORTS = setOf("kotlin.jvm.JvmInline")
    private const val INTEGRATION_SDK_PATH = "shared/integration-sdk/"
    private const val KTOR_CLIENT_PACKAGE = "io.ktor.client"

    /** services/<service>/<layer>/ 配下のファイルについて、同一サービス内の依存方向を検査する。 */
    fun layerDependencies(
        codeBase: CodeBase,
        service: String,
    ): List<Violation> =
        serviceLayerFiles(codeBase, service).flatMap { (layer, file) ->
            val allowed = ALLOWED_LAYER_DEPENDENCIES.getValue(layer)
            file.importNames.mapNotNull { import ->
                val target = layerOf(import, service)
                if (target != null && target != layer && target !in allowed) {
                    Violation("依存方向", file.path, "$layer が $target を参照しています: import $import")
                } else {
                    null
                }
            }
        }

    /** サービス間のコード依存(MODULE_DESIGN §2)と、domain / application から platform への依存を検査する。 */
    fun serviceIsolation(
        codeBase: CodeBase,
        service: String,
    ): List<Violation> {
        val otherServices = codeBase.services - service
        return serviceLayerFiles(codeBase, service).flatMap { (layer, file) ->
            file.importNames.mapNotNull { import ->
                val otherService = otherServices.firstOrNull { import.isInPackage("$BASE_PACKAGE.$it") }
                when {
                    otherService != null -> {
                        Violation("サービス間依存", file.path, "$service が $otherService を参照しています: import $import")
                    }

                    layer in setOf("domain", "application") && import.isInPackage("$BASE_PACKAGE.platform") -> {
                        Violation("依存方向", file.path, "$layer が platform を参照しています: import $import")
                    }

                    else -> {
                        null
                    }
                }
            }
        }
    }

    /** services/<service>/<layer>/ 配下のファイルのパッケージが `io.eia.<service>.<layer>` で始まること。 */
    fun packageMatchesLocation(
        codeBase: CodeBase,
        service: String,
    ): List<Violation> =
        serviceLayerFiles(codeBase, service).mapNotNull { (layer, file) ->
            val expected = "$BASE_PACKAGE.$service.$layer"
            if (file.packageName.isInPackage(expected)) {
                null
            } else {
                Violation("パッケージ配置", file.path, "package '${file.packageName}' は $expected 配下である必要があります")
            }
        }

    /** platform 配下のモジュールは services 配下に依存しない(MODULE_DESIGN §2)。 */
    fun platformIndependentOfServices(codeBase: CodeBase): List<Violation> =
        codeBase.filesUnder("platform/").flatMap { file ->
            file.importNames.mapNotNull { import ->
                codeBase.services.firstOrNull { import.isInPackage("$BASE_PACKAGE.$it") }?.let {
                    Violation("platform→services 依存", file.path, "platform が services:$it を参照しています: import $import")
                }
            }
        }

    /** services の domain / application と shared 配下の commonMain は ADR-0004 §2 の禁止 import を含まない。 */
    fun commonMainPurity(codeBase: CodeBase): List<Violation> =
        codeBase.files
            .filter { it.isPureCommonMain() }
            .flatMap { file ->
                file.importNames.mapNotNull { import ->
                    val forbidden = FORBIDDEN_COMMON_MAIN_PACKAGES.firstOrNull { import.isInPackage(it) }
                    val allowed =
                        import in COMMON_MAIN_ALLOWED_IMPORTS ||
                            (file.path.startsWith(INTEGRATION_SDK_PATH) && import.isInPackage(KTOR_CLIENT_PACKAGE))
                    if (forbidden != null && !allowed) {
                        Violation("commonMain 禁止 import", file.path, "import $import($forbidden.* は commonMain で禁止)")
                    } else {
                        null
                    }
                }
            }

    /** ADR-0004 §3: kotlin.Result と runCatching を使わない(全モジュール)。 */
    fun noKotlinResult(codeBase: CodeBase): List<Violation> {
        val packagesDeclaringResult =
            codeBase.files
                .filter { file -> DECLARES_RESULT.containsMatchIn(file.code) }
                .map { it.packageName }
                .toSet()
        return codeBase.files.flatMap { file ->
            val resultIsShadowed =
                file.packageName in packagesDeclaringResult ||
                    "Result" in file.importAliases ||
                    file.importNames.any { it.endsWith(".Result") && it != KOTLIN_RESULT }
            buildList {
                if (KOTLIN_RESULT in file.importNames) {
                    add(Violation("kotlin.Result 禁止", file.path, "import $KOTLIN_RESULT"))
                }
                if (QUALIFIED_KOTLIN_RESULT.containsMatchIn(file.code)) {
                    add(Violation("kotlin.Result 禁止", file.path, "kotlin.Result を完全修飾名で使用しています"))
                }
                if (!resultIsShadowed && BARE_RESULT_TYPE.containsMatchIn(file.code)) {
                    add(
                        Violation(
                            "kotlin.Result 禁止",
                            file.path,
                            "Result<...> が kotlin.Result に解決されます(io.eia.shared.kernel.Result を import する)",
                        ),
                    )
                }
                if (RUN_CATCHING.containsMatchIn(file.code)) {
                    add(Violation("runCatching 禁止", file.path, "runCatching を使用しています(io.eia.shared.kernel.catching を使う)"))
                }
            }
        }
    }

    /**
     * shared の基盤モジュールの commonMain が import してよいパッケージ(許可リスト。ADR-0010 §6)。
     * 禁止リスト([commonMainPurity])だけでは新しいライブラリの混入を検出できないため、フレームワーク依存ゼロを許可リストで担保する。
     */
    val SHARED_ALLOWED_IMPORTS: Map<String, List<String>> =
        mapOf(
            "shared/kernel/" to listOf("kotlin", "$BASE_PACKAGE.shared.kernel"),
            "shared/canonical-model/" to
                listOf("kotlin", "kotlinx.serialization", "$BASE_PACKAGE.shared.kernel", "$BASE_PACKAGE.shared.canonical"),
            "shared/resilience/" to listOf("kotlin", "$BASE_PACKAGE.shared.kernel", "$BASE_PACKAGE.shared.resilience"),
        )

    /** shared の基盤モジュール(kernel / canonical-model / resilience)の commonMain は [SHARED_ALLOWED_IMPORTS] 以外を import しない。 */
    fun sharedImportAllowList(codeBase: CodeBase): List<Violation> =
        SHARED_ALLOWED_IMPORTS.flatMap { (modulePath, allowed) ->
            codeBase
                .filesUnder(modulePath)
                .filter { it.path.contains("/src/commonMain/") }
                .flatMap { file ->
                    file.importNames
                        .filter { import -> allowed.none { import.isInPackage(it) } }
                        .map { Violation("shared 許可リスト外の import", file.path, "import $it(許可: ${allowed.joinToString()})") }
                }
        }

    private const val TEST_SUPPORT_PACKAGE = "$BASE_PACKAGE.platform.testsupport"
    private const val TEST_SUPPORT_PATH = "platform/test-support/"
    private val TEST_SOURCE_SET = Regex("""/src/(test|integrationTest|e2eTest|[A-Za-z0-9]+Test)/kotlin/""")

    private val TEST_SUPPORT_QUALIFIED_USE = Regex("""(?<![\w.])${Regex.escape(TEST_SUPPORT_PACKAGE)}\.""")
    private val IMPORT_OR_PACKAGE_LINE = Regex("""^\s*(import|package)\s.*$""", RegexOption.MULTILINE)

    /**
     * platform/test-support(ADR-0016 §5)はテストのソースセットからだけ参照する。本番コードに Testcontainers を持ち込まない。
     * import と完全修飾名での参照の両方を検査する。Gradle の依存宣言(main のクラスパスに載ること)は build-logic が検査する。
     */
    fun testSupportOnlyFromTests(codeBase: CodeBase): List<Violation> =
        codeBase.files
            .filter { !it.path.startsWith(TEST_SUPPORT_PATH) && !TEST_SOURCE_SET.containsMatchIn(it.path) }
            .flatMap { file ->
                val imports =
                    file.importNames
                        .filter { it.isInPackage(TEST_SUPPORT_PACKAGE) }
                        .map { Violation("test-support はテスト専用", file.path, "テスト以外のソースセットから import $it") }
                val qualified =
                    if (TEST_SUPPORT_QUALIFIED_USE.containsMatchIn(file.code.replace(IMPORT_OR_PACKAGE_LINE, ""))) {
                        listOf(Violation("test-support はテスト専用", file.path, "テスト以外のソースセットから完全修飾名で参照しています"))
                    } else {
                        emptyList()
                    }
                imports + qualified
            }

    /**
     * DomainError の Retryable と NonRetryable の両方を(間接的な継承を含めて)実装する型を禁止する(ADR-0011)。
     * 継承関係は単純名で辿るため、同名の別の型があると誤検知しうる。
     */
    fun domainErrorKindIsExclusive(codeBase: CodeBase): List<Violation> {
        val types =
            codeBase.files.flatMap { file ->
                val declarations =
                    file.declaration.classes(includeNested = true, includeLocal = true) +
                        file.declaration.interfaces(includeNested = true) +
                        file.declaration.objects(includeNested = true)
                declarations.map { Triple(file.path, it.name, it.parents().map { parent -> parent.name.simpleTypeName() }) }
            }
        val parentsByName = types.groupBy({ it.second }, { it.third }).mapValues { (_, lists) -> lists.flatten().toSet() }

        fun ancestors(name: String): Set<String> {
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(parentsByName[name].orEmpty())
            while (queue.isNotEmpty()) {
                val parent = queue.removeFirst()
                if (seen.add(parent)) queue += parentsByName[parent].orEmpty()
            }
            return seen
        }

        return types.mapNotNull { (path, name, _) ->
            val all = ancestors(name)
            if (RETRYABLE in all && NON_RETRYABLE in all) {
                Violation("DomainError 分類の排他", path, "$name が Retryable と NonRetryable の両方を実装しています")
            } else {
                null
            }
        }
    }

    private const val RETRYABLE = "Retryable"
    private const val NON_RETRYABLE = "NonRetryable"

    /** `DomainError.Retryable` や `Foo<Bar>` から単純名を取り出す。 */
    private fun String.simpleTypeName(): String = substringBefore('<').substringAfterLast('.').trim()

    private const val KOTLIN_RESULT = "kotlin.Result"
    private val DECLARES_RESULT = Regex("""\b(class|interface|typealias)\s+Result\b""")
    private val QUALIFIED_KOTLIN_RESULT = Regex("""(?<![\w.])kotlin\.Result\b""")
    private val BARE_RESULT_TYPE = Regex("""(?<![\w.])Result\s*<""")
    private val RUN_CATCHING = Regex("""\brunCatching\s*[{(<]""")

    private fun serviceLayerFiles(
        codeBase: CodeBase,
        service: String,
    ): List<Pair<String, SourceFile>> =
        codeBase.filesUnder("services/$service/").mapNotNull { file ->
            val layer = file.path.split('/')[2]
            if (layer in LAYERS) layer to file else null
        }

    /** import が `io.eia.<service>.<layer>` 配下なら layer を返す。 */
    private fun layerOf(
        import: String,
        service: String,
    ): String? = LAYERS.firstOrNull { import.isInPackage("$BASE_PACKAGE.$service.$it") }

    private fun SourceFile.isPureCommonMain(): Boolean {
        if (!path.contains("/src/commonMain/")) return false
        val segments = path.split('/')
        return segments[0] == "shared" || (segments[0] == "services" && segments[2] in setOf("domain", "application"))
    }

    private fun String.isInPackage(packageName: String): Boolean = this == packageName || startsWith("$packageName.")
}
