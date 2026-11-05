package io.eia.tools.architecture

import java.io.File

/**
 * アラートの運用の規則(ROADMAP P14 の DoD: 全アラートが Runbook に紐づいている。ADR-0045)。Prometheus のルールのファイルをテキストで読む。
 * ルールの正しさ(発火の条件)は promtool のテストと `make verify` で確かめる。
 */
internal object AlertGovernanceRules {
    private const val RULES_DIR = "infra/local/prometheus/rules"
    private val RULE_START = Regex("""\n(?=\s*- (?:alert|record): )""")
    private val ALERT = Regex("""^\s*- alert: (\S+)""")
    private val RUNBOOK = Regex("""runbook: (\S+)""")
    private val SEVERITY = Regex("""severity: (critical|warning|info)\b""")
    private val SUMMARY = Regex("""summary: """)
    private val HEADING = Regex("""^#+ (.*)$""", RegexOption.MULTILINE)

    /** 全アラートの違反。重大度と要約があり、Runbook のファイルと見出しがあり、Runbook にアラートの名前が書いてあること。 */
    fun alertViolations(root: File): List<Violation> =
        ruleFiles(root).flatMap { file ->
            val path = file.relativeTo(root).path
            file.readText().split(RULE_START).flatMap { block ->
                val name = ALERT.find(block)?.groupValues?.get(1)
                if (name == null) emptyList() else violationsOf(root, path, name, block)
            }
        }

    /** アラートの名前の一覧(検査が 0 件で成功しないことの確認に使う)。 */
    fun alertNames(root: File): List<String> =
        ruleFiles(root).flatMap { file ->
            file.readText().split(RULE_START).mapNotNull { ALERT.find(it)?.groupValues?.get(1) }
        }

    private fun ruleFiles(root: File): List<File> =
        root
            .resolve(RULES_DIR)
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".rules.yml") }
            .sortedBy { it.name }

    private fun violationsOf(
        root: File,
        path: String,
        name: String,
        block: String,
    ): List<Violation> =
        buildList {
            if (!SEVERITY.containsMatchIn(block)) add(Violation(RULE, path, "$name に severity がない"))
            if (!SUMMARY.containsMatchIn(block)) add(Violation(RULE, path, "$name に summary がない"))
            val link = RUNBOOK.find(block)?.groupValues?.get(1)
            if (link == null) {
                add(Violation(RULE, path, "$name に runbook がない"))
            } else {
                addAll(linkViolations(root, path, name, link))
            }
        }

    private fun linkViolations(
        root: File,
        path: String,
        name: String,
        link: String,
    ): List<Violation> {
        val target = link.substringBefore('#')
        val anchor = link.substringAfter('#', "")
        val runbook = root.resolve(target)
        if (!runbook.isFile) return listOf(Violation(RULE, path, "$name の Runbook $target がない"))
        val text = runbook.readText()
        return buildList {
            if (anchor.isEmpty()) {
                add(Violation(RULE, path, "$name の Runbook のリンクに見出しがない($target)"))
            } else if (HEADING.findAll(text).none { slug(it.groupValues[1]) == anchor }) {
                add(Violation(RULE, path, "$name の Runbook に見出し #$anchor がない($target)"))
            }
            if (name !in text) add(Violation(RULE, path, "$name が Runbook $target に書かれていない"))
        }
    }

    /** GitHub の見出しのアンカー。小文字にし、英数字・かな漢字・空白・`-`・`_` 以外を消し、空白を `-` にする。 */
    internal fun slug(heading: String): String =
        heading
            .trim()
            .lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')

    /** SLO の定義(ルールの `slo` のラベル → カタログの連携と、見る項目)。 */
    data class SloDefinition(
        val slo: String,
        val integration: String,
        val kind: Kind,
    ) {
        enum class Kind { AVAILABILITY, LATENCY_P99 }
    }

    val SLOS: List<SloDefinition> =
        listOf(
            SloDefinition("order-api-availability", "INT-SALES-001", SloDefinition.Kind.AVAILABILITY),
            SloDefinition("order-api-latency", "INT-SALES-001", SloDefinition.Kind.LATENCY_P99),
            SloDefinition("storefront-availability", "INT-STOREFRONT-001", SloDefinition.Kind.AVAILABILITY),
            SloDefinition("storefront-latency", "INT-STOREFRONT-001", SloDefinition.Kind.LATENCY_P99),
            SloDefinition("order-events-publish", "INT-SALES-002", SloDefinition.Kind.AVAILABILITY),
        )

    private val CATALOG_SLO = Regex("""^slo: \{ availability: "([\d.]+)", latencyP99: "(\d+)(ms|s)" }""", RegexOption.MULTILINE)
    private const val P99_BUDGET = 0.01
    private const val PERCENT = 100.0
    private const val MILLIS_PER_SECOND = 1000
    private const val TOLERANCE = 1e-9

    /** ルールの誤差の予算と遅延の境界が、カタログの SLO と一致すること(目標が 2 か所にあるため。ADR-0045)。 */
    fun sloViolations(
        root: File,
        slos: List<SloDefinition> = SLOS,
    ): List<Violation> {
        val rulesFile = root.resolve("$RULES_DIR/slo.rules.yml")
        val rules = rulesFile.readText()
        return slos.flatMap { definition ->
            val catalog = root.resolve("contracts/catalog/${definition.integration}.yaml").readText()
            val target = CATALOG_SLO.find(catalog)
            if (target == null) {
                listOf(Violation(SLO_RULE, rulesFile.path, "${definition.integration} のカタログに SLO がない"))
            } else {
                sloViolation(rules, definition, target)
            }
        }
    }

    private fun sloViolation(
        rules: String,
        definition: SloDefinition,
        target: MatchResult,
    ): List<Violation> {
        val (availability, latency, unit) = target.destructured
        val expected =
            when (definition.kind) {
                SloDefinition.Kind.AVAILABILITY -> (PERCENT - availability.toDouble()) / PERCENT
                SloDefinition.Kind.LATENCY_P99 -> P99_BUDGET
            }
        val budget =
            Regex("""record: slo:error_budget\s+expr: vector\(([\d.]+)\)\s+labels:\s+slo: ${Regex.escape(definition.slo)}\b""")
                .find(rules)
                ?.groupValues
                ?.get(1)
                ?.toDouble()
        return buildList {
            if (budget == null || kotlin.math.abs(budget - expected) > TOLERANCE) {
                add(Violation(SLO_RULE, RULES_DIR, "${definition.slo} の誤差の予算 $budget が、カタログの値 $expected と違う"))
            }
            if (definition.kind == SloDefinition.Kind.LATENCY_P99) {
                val millis = latency.toInt() * if (unit == "s") MILLIS_PER_SECOND else 1
                val bound =
                    Regex("""le="([\d.]+)"[^\n]*\n\s+labels:\s+slo: ${Regex.escape(definition.slo)}\b""")
                        .find(rules)
                        ?.groupValues
                        ?.get(1)
                        ?.toDouble()
                if (bound != millis.toDouble()) {
                    add(Violation(SLO_RULE, RULES_DIR, "${definition.slo} の遅延の境界 $bound ms が、カタログの値 $millis ms と違う"))
                }
            }
        }
    }

    private const val RULE = "アラートと Runbook"
    private const val SLO_RULE = "SLO とカタログ"
}
