package io.eia.integrationflow.adapters.flow

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** Flow の定義のファイル([name] はファイル名。誤りのメッセージに使う)。 */
public class FlowFile(
    public val name: String,
    public val text: String,
)

/**
 * Flow の定義の置き場。既定はイメージに含めた contracts/flows([Classpath])。ディレクトリ([Directory])を指定すると、
 * そこにある `*.flow.yaml` を読む(Flow の YAML を足して再起動するだけで、新しいマッピングを足せる。ROADMAP P10 の DoD)。
 * [Combined] は複数の置き場を合わせる(イメージの Flow に、追加のディレクトリの Flow を足す)。
 */
public sealed interface FlowSource {
    /** ファイルを読む。読めなければ、ファイル名と理由の一覧。 */
    public fun files(): Result

    public sealed interface Result {
        public class Files(
            public val files: List<FlowFile>,
        ) : Result

        public class Unreadable(
            public val problems: List<Pair<String, String>>,
        ) : Result
    }

    /** ビルド時にリソースに含めた contracts/flows(一覧は index.txt)。 */
    public data object Classpath : FlowSource {
        private const val BASE = "/contracts/flows/"

        override fun files(): Result {
            val index = resource("index.txt") ?: return Result.Unreadable(listOf("${BASE}index.txt" to "リソースにありません"))
            val names = index.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val missing = names.filter { resource(it) == null }
            return if (missing.isEmpty()) {
                Result.Files(names.map { FlowFile(it, checkNotNull(resource(it))) })
            } else {
                Result.Unreadable(missing.map { "$BASE$it" to "リソースにありません" })
            }
        }

        private fun resource(name: String): String? = FlowSource::class.java.getResource("$BASE$name")?.readText()
    }

    /** ディレクトリの `*.flow.yaml`(ファイル名の順)。 */
    public data class Directory(
        val path: Path,
    ) : FlowSource {
        override fun files(): Result =
            try {
                val files =
                    Files.list(path).use { stream ->
                        stream
                            .toList()
                            .filter { it.isRegularFile() && it.name.endsWith(SUFFIX) }
                            .sortedBy { it.name }
                            .map { FlowFile(it.name, it.readText()) }
                    }
                Result.Files(files)
            } catch (e: IOException) {
                Result.Unreadable(listOf(path.toString() to "読めません(${e::class.simpleName})"))
            }
    }

    /**
     * 複数の置き場を合わせたもの(例 イメージの contracts/flows と、試験用の追加のディレクトリ)。どれかを読めなければ読めない。
     * 同じ ID の Flow が重なれば、読み込み([io.eia.integrationflow.domain.FlowCatalog])が誤りにする。
     */
    public data class Combined(
        val sources: List<FlowSource>,
    ) : FlowSource {
        override fun files(): Result {
            val results = sources.map { it.files() }
            val problems = results.filterIsInstance<Result.Unreadable>().flatMap { it.problems }
            return if (problems.isEmpty()) {
                Result.Files(
                    results.filterIsInstance<Result.Files>().flatMap { it.files },
                )
            } else {
                Result.Unreadable(problems)
            }
        }
    }

    public companion object {
        public const val SUFFIX: String = ".flow.yaml"
    }
}
