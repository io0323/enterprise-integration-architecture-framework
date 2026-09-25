package io.eia.tools.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import java.io.File

/** 検査対象の Kotlin ソース 1 ファイル。[path] はリポジトリ(またはフィクスチャ)ルートからの相対パス。 */
internal class SourceFile(
    val path: String,
    val declaration: KoFileDeclaration,
) {
    val packageName: String get() = declaration.packagee?.name.orEmpty()

    val importNames: List<String> get() = declaration.imports.map { it.name }

    val importAliases: List<String> get() = declaration.imports.mapNotNull { it.alias?.name }

    /** コメントと文字列リテラルを除いた本文。呼び出しや型の出現をテキストで検査するときに使う。 */
    val code: String by lazy { KotlinSourceText.stripCommentsAndStrings(declaration.text) }
}

/**
 * リポジトリのレイアウト(MODULE_DESIGN §1)に従ってソースを収集する。
 * 対象は `{services,shared,platform,tools,tests}/.../src/<sourceSet>/kotlin/` 配下の .kt のみ(resources の違反サンプルは含めない)。
 */
internal class CodeBase(
    val root: File,
) {
    val services: List<String> =
        root
            .resolve("services")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
            .sorted()

    val files: List<SourceFile> =
        TOP_LEVEL_DIRECTORIES
            .map { root.resolve(it) }
            .filter { it.isDirectory && it.walkTopDown().any { f -> f.extension == "kt" } }
            .flatMap { Konsist.scopeFromExternalDirectory(it.absolutePath).files }
            .map { SourceFile(File(it.path).relativeTo(root).invariantSeparatorsPath, it) }
            .filter { SOURCE_PATH.containsMatchIn(it.path) }
            .sortedBy { it.path }

    fun filesUnder(prefix: String): List<SourceFile> = files.filter { it.path.startsWith(prefix) }

    companion object {
        private val TOP_LEVEL_DIRECTORIES = listOf("services", "shared", "platform", "tools", "tests")

        // <top>/<module>[/<submodule>]/src/<sourceSet>/kotlin/
        private val SOURCE_PATH = Regex("""^(services|shared|platform|tools|tests)/[^/]+(/[^/]+)?/src/[^/]+/kotlin/""")

        fun fromSystemProperty(): CodeBase {
            val rootDir =
                checkNotNull(System.getProperty("eia.rootDir")) {
                    "システムプロパティ eia.rootDir が未設定です(Gradle の test タスクから実行してください)"
                }
            return CodeBase(File(rootDir))
        }
    }
}

internal object KotlinSourceText {
    /** 行コメント・ブロックコメント・文字列リテラル(raw string 含む)を空白に置き換える。 */
    fun stripCommentsAndStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val next = skipNonCode(text, i)
            if (next == i) {
                out.append(text[i])
                i++
            } else {
                out.append(' ')
                i = next
            }
        }
        return out.toString()
    }

    /** [start] がコメント・文字列の開始ならその終端の次の位置を、そうでなければ [start] を返す。 */
    private fun skipNonCode(
        text: String,
        start: Int,
    ): Int =
        when {
            text.startsWith("//", start) -> text.indexOf('\n', start).let { if (it < 0) text.length else it }
            text.startsWith("/*", start) -> text.indexOf("*/", start + 2).let { if (it < 0) text.length else it + 2 }
            text.startsWith("\"\"\"", start) -> text.indexOf("\"\"\"", start + 3).let { if (it < 0) text.length else it + 3 }
            text[start] == '"' || text[start] == '\'' -> skipQuoted(text, start, text[start])
            else -> start
        }

    private fun skipQuoted(
        text: String,
        start: Int,
        quote: Char,
    ): Int {
        var i = start + 1
        while (i < text.length && text[i] != quote && text[i] != '\n') {
            i += if (text[i] == '\\') 2 else 1
        }
        return minOf(i + 1, text.length)
    }
}
