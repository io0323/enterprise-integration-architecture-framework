package io.eia.tools.contract

/** 検査結果の出力。各違反を「ファイル / ルール ID / 内容 / Framework 章 / 修正方法」の 5 項目で表す。 */
object Report {
    private val HEADER = listOf("ファイル", "ルール ID", "内容", "Framework 章", "修正方法")

    fun text(result: CheckResult): String =
        buildString {
            appendLine(summary(result))
            result.violations.forEach { v ->
                appendLine()
                appendLine("${v.file}")
                appendLine("  ルール ID    : ${v.rule.id}(${v.rule.title})")
                appendLine("  内容         : ${v.message}")
                appendLine("  Framework 章 : ${v.rule.chapter}")
                appendLine("  修正方法     : ${v.rule.fix}")
            }
        }

    /** GitHub Actions の Step Summary 用の Markdown。 */
    fun markdown(result: CheckResult): String =
        buildString {
            appendLine("## contract-check")
            appendLine()
            appendLine(summary(result))
            if (result.violations.isEmpty()) return@buildString
            appendLine()
            appendLine(HEADER.joinToString(" | ", "| ", " |"))
            appendLine(HEADER.joinToString(" | ", "| ", " |") { "---" })
            result.violations.forEach { v ->
                val cells = listOf("`${v.file}`", "`${v.rule.id}`", v.message, v.rule.chapter, v.rule.fix)
                appendLine(cells.joinToString(" | ", "| ", " |") { it.escapeCell() })
            }
        }

    private fun summary(result: CheckResult): String {
        val baseline = if (result.baselineUsed) "" else "(比較元がないため互換性検査 CC-COMPAT-* は省略)"
        return if (result.violations.isEmpty()) {
            "違反はありません$baseline"
        } else {
            "違反が ${result.violations.size} 件あります$baseline"
        }
    }

    private fun String.escapeCell(): String = replace("|", "\\|").replace("\r", "").replace("\n", "<br>")
}
