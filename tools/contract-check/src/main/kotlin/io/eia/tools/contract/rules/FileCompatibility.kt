package io.eia.tools.contract.rules

import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import tools.jackson.databind.JsonNode

/** ファイルの仕様(contracts/files/{system}_{dataset}.v{n}.yaml)の、main の版に対する互換性(CC-COMPAT-003)。 */
object FileCompatibility {
    /**
     * main の版に対する互換性(CC-COMPAT-003)。同じパス(同じ版)の仕様だけを比べる。
     * 足してよいのは末尾の任意の列と新しい形式だけ。受ける側は列の位置(CSV)と型・必須性・enum の値で検査するため、それ以外は互換でない。
     */
    fun check(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> {
        val previous = baseline.fileSpecDocuments.associateBy { it.path }
        return current.fileSpecDocuments.flatMap { document ->
            val old = previous[document.path] ?: return@flatMap emptyList()
            (compareColumns(FileRules.columns(old.tree), FileRules.columns(document.tree)) + compareFormats(old.tree, document.tree))
                .map { Violation(document.path, Rule.COMPAT_FILE, it) }
        }
    }

    private fun compareColumns(
        old: List<FileRules.Column>,
        new: List<FileRules.Column>,
    ): List<String> {
        val problems = mutableListOf<String>()
        old.forEachIndexed { index, before ->
            val after = new.getOrNull(index)
            when {
                after == null -> problems += "列 '${before.name}' を削除しました"
                after.name != before.name -> problems += "列 ${index + 1} が '${before.name}' から '${after.name}' に変わりました(削除・名前の変更・並べ替え)"
                after != before -> problems += "列 '${before.name}' の型・必須性・enum の値が変わりました"
            }
        }
        new.drop(old.size).filter { it.nullable != true }.forEach { problems += "末尾に足した列 '${it.name}' が nullable: true ではありません" }
        return problems
    }

    private fun compareFormats(
        old: JsonNode,
        new: JsonNode,
    ): List<String> {
        val before =
            old
                .get("formats")
                ?.values()
                .orEmpty()
                .associateBy { it.get("type")?.stringValue() }
        val after =
            new
                .get("formats")
                ?.values()
                .orEmpty()
                .associateBy { it.get("type")?.stringValue() }
        return before.mapNotNull { (type, format) ->
            when (after[type]) {
                null -> "形式 '$type' を削除しました"
                format -> null
                else -> "形式 '$type' の設定(拡張子・圧縮・区切りなど)が変わりました"
            }
        }
    }
}
