package io.eia.tools.architecture

import java.io.File

/**
 * NFR のレポート(docs/reports/p14-nfr.md。P14 ⑩・ADR-0048)の連携の一覧が、カタログ(contracts/catalog/INT-*.yaml)と一致すること。
 * **一覧だけを確かめ、計測の数値は確かめない**(時間に依存する検査を CI に入れない。ROADMAP P14)。
 * 表は scripts/reports/nfr-table.py がカタログと計測の結果(docs/reports/p14-nfr-integrations.json)から作る。
 */
internal object NfrReportRules {
    const val REPORT = "docs/reports/p14-nfr.md"
    const val RESULTS = "docs/reports/p14-nfr-integrations.json"
    private const val BEGIN = "<!-- nfr-table:begin -->"
    private const val END = "<!-- nfr-table:end -->"
    private val CATALOG_ID = Regex("""^id:\s*(INT-[A-Z0-9-]+)\s*$""", RegexOption.MULTILINE)
    private val ROW_ID = Regex("""^\|\s*(INT-[A-Z0-9-]+)\s*\|""", RegexOption.MULTILINE)
    private val RESULT_KEY = Regex(""""(INT-[A-Z0-9-]+)"\s*:\s*\{""")

    fun catalogIds(root: File): List<String> =
        root
            .resolve("contracts/catalog")
            .listFiles { file -> file.name.startsWith("INT-") && file.name.endsWith(".yaml") }
            .orEmpty()
            .mapNotNull { CATALOG_ID.find(it.readText())?.groupValues?.get(1) }
            .sorted()

    fun violations(root: File): List<Violation> {
        val report = root.resolve(REPORT)
        val results = root.resolve(RESULTS)
        val text = report.takeIf { it.isFile }?.readText()
        val start = text?.indexOf(BEGIN) ?: -1
        val end = text?.indexOf(END) ?: -1
        val problem =
            when {
                text == null -> Violation("NFR のレポート", REPORT, "ファイルがありません")
                !results.isFile -> Violation("NFR のレポート", RESULTS, "ファイルがありません")
                start < 0 || end < start -> Violation("NFR のレポート", REPORT, "$BEGIN と $END の間に表がありません")
                else -> null
            }
        if (problem != null || text == null) return listOfNotNull(problem)
        val catalog = catalogIds(root)
        val rows = ROW_ID.findAll(text.substring(start, end)).map { it.groupValues[1] }.toList()
        val keys = RESULT_KEY.findAll(results.readText()).map { it.groupValues[1] }.toList()
        return compare(REPORT, "表の行", rows, catalog) + compare(RESULTS, "結果の項目", keys, catalog)
    }

    private fun compare(
        path: String,
        what: String,
        listed: List<String>,
        catalog: List<String>,
    ): List<Violation> =
        buildList {
            (catalog - listed.toSet()).forEach {
                add(Violation("NFR のレポート", path, "$it が$what にありません(scripts/reports/nfr-table.py --write)"))
            }
            (listed.toSet() - catalog.toSet()).forEach { add(Violation("NFR のレポート", path, "$it はカタログにありません")) }
            listed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
                add(Violation("NFR のレポート", path, "$it が$what に 2 回以上あります"))
            }
        }
}
