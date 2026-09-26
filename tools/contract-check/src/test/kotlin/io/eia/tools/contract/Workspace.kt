package io.eia.tools.contract

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * テスト用の作業ディレクトリ。実際の contracts/ を複製し、違反サンプル(fixture の重ね合わせ・文字列の置換)を加える。
 * 実際の契約を起点にするため、契約を変更しても違反サンプルが古くならない。
 */
class Workspace private constructor(
    val root: Path,
) {
    /** src/test/resources/fixtures/[name]/ の内容を重ねる(同じパスのファイルは置き換える)。 */
    fun overlay(name: String): Workspace {
        val fixture = Path.of(checkNotNull(Workspace::class.java.getResource("/fixtures/$name")) { "fixture '$name' がありません" }.toURI())
        fixture.toFile().copyRecursively(root.toFile(), overwrite = true)
        return this
    }

    /** [path] の [old] を [new] に置き換える。[old] が見つからなければテストの誤りとして失敗させる。 */
    fun replace(
        path: String,
        old: String,
        new: String,
    ): Workspace {
        val file = root.resolve(path)
        val text = file.readText()
        check(old in text) { "$path に置換対象がありません: $old" }
        file.writeText(text.replace(old, new))
        return this
    }

    fun delete(path: String): Workspace {
        root.resolve(path).deleteExisting()
        return this
    }

    fun rename(
        from: String,
        to: String,
    ): Workspace {
        root.resolve(from).moveTo(root.resolve(to))
        return this
    }

    fun check(
        baseline: Workspace? = null,
        oasdiff: Path? = OASDIFF,
    ): CheckResult = ContractCheck(root, baseline?.root, oasdiff).run()

    companion object {
        val REPOSITORY_ROOT: Path = Path(checkNotNull(System.getProperty("eia.rootDir")) { "eia.rootDir が未設定です" })
        val OASDIFF: Path? = System.getProperty("eia.oasdiff")?.let(::Path)

        /** 実際の contracts/ を複製した作業ディレクトリ。 */
        fun ofRepositoryContracts(): Workspace {
            val root = createTempDirectory("contract-check")
            val source = REPOSITORY_ROOT.resolve(Contracts.CONTRACTS_DIR)
            source.toFile().walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(Contracts.CONTRACTS_DIR).resolve(source.relativize(file.toPath()))
                target.parent.createDirectories()
                file.toPath().copyTo(target)
            }
            return Workspace(root)
        }
    }
}

val CheckResult.ruleIds: Set<String> get() = violations.map { it.rule.id }.toSet()
