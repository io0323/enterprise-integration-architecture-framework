package io.eia.tools.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

private fun workspace(
    rules: String,
    runbook: String? = "# Runbook\n\nGood を見る。\n\n## 対応の手順\n",
): File {
    val root = Files.createTempDirectory("alert-governance").toFile()
    root.resolve("infra/local/prometheus/rules").mkdirs()
    root.resolve("infra/local/prometheus/rules/a.rules.yml").writeText(rules)
    if (runbook != null) {
        root.resolve("docs/runbooks").mkdirs()
        root.resolve("docs/runbooks/a.md").writeText(runbook)
    }
    return root
}

private fun alert(
    name: String = "Good",
    severity: String? = "warning",
    summary: Boolean = true,
    runbook: String? = "docs/runbooks/a.md#対応の手順",
): String =
    buildString {
        appendLine("groups:\n  - name: g\n    rules:")
        appendLine("      - alert: $name")
        appendLine("        expr: up == 0")
        severity?.let { appendLine("        labels:\n          severity: $it") }
        appendLine("        annotations:")
        if (summary) appendLine("          summary: x")
        runbook?.let { appendLine("          runbook: $it") }
    }

private const val SLO_RULES = """
      - record: slo:error_budget
        expr: vector(0.0005)
        labels:
          slo: api-availability
      - record: slo:error_budget
        expr: vector(0.01)
        labels:
          slo: api-latency
      - record: slo:error_ratio:rate5m
        expr: 1 - sum(rate(x_bucket{le="500.0"}[5m]))
        labels:
          slo: api-latency
"""

private fun sloWorkspace(catalog: String): File {
    val root = Files.createTempDirectory("slo-governance").toFile()
    root.resolve("infra/local/prometheus/rules").mkdirs()
    root.resolve("infra/local/prometheus/rules/slo.rules.yml").writeText(SLO_RULES)
    root.resolve("contracts/catalog").mkdirs()
    root.resolve("contracts/catalog/INT-A.yaml").writeText("id: INT-A\n$catalog\n")
    return root
}

private val TEST_SLOS =
    listOf(
        AlertGovernanceRules.SloDefinition("api-availability", "INT-A", AlertGovernanceRules.SloDefinition.Kind.AVAILABILITY),
        AlertGovernanceRules.SloDefinition("api-latency", "INT-A", AlertGovernanceRules.SloDefinition.Kind.LATENCY_P99),
    )

class AlertGovernanceSpec :
    FunSpec({
        val repository = File(checkNotNull(System.getProperty("eia.rootDir")))

        context("リポジトリの全アラート(ROADMAP P14 の DoD)") {
            test("検査の対象がある(0 件で成功しない)") {
                AlertGovernanceRules.alertNames(repository).size shouldBeGreaterThan 50
            }

            test("重大度・要約・Runbook のファイルと見出しがあり、Runbook にアラートの名前が書いてある") {
                AlertGovernanceRules.alertViolations(repository).shouldBeEmpty()
            }

            test("SLO のルールの予算と遅延の境界が、カタログと一致する") {
                AlertGovernanceRules.sloViolations(repository).shouldBeEmpty()
            }
        }

        context("違反を検出する") {
            test("規則を満たすアラートは違反にならない") {
                AlertGovernanceRules.alertViolations(workspace(alert())).shouldBeEmpty()
            }

            mapOf(
                "severity がない" to alert(severity = null),
                "summary がない" to alert(summary = false),
                "runbook がない" to alert(runbook = null),
                "Runbook のファイルがない" to alert(runbook = "docs/runbooks/none.md#対応の手順"),
                "Runbook の見出しがない" to alert(runbook = "docs/runbooks/a.md#ない見出し"),
                "リンクに見出しがない" to alert(runbook = "docs/runbooks/a.md"),
                "Runbook にアラートの名前がない" to alert(name = "Unnamed"),
            ).forEach { (label, rules) ->
                test(label) { AlertGovernanceRules.alertViolations(workspace(rules)) shouldHaveSize 1 }
            }

            test("見出しのアンカーは GitHub の形(小文字。記号を消す。空白は -)") {
                AlertGovernanceRules.slug("BFF の枠の使用率が高いとき") shouldBe "bff-の枠の使用率が高いとき"
                AlertGovernanceRules.slug("Foo(Bar) baz") shouldBe "foobar-baz"
            }

            test("SLO がカタログと一致すれば違反にならない") {
                val root = sloWorkspace("""slo: { availability: "99.95", latencyP99: "500ms" }""")
                AlertGovernanceRules.sloViolations(root, TEST_SLOS).shouldBeEmpty()
            }

            test("カタログの可用性が変わると、ルールの予算との違いを検出する") {
                val root = sloWorkspace("""slo: { availability: "99.9", latencyP99: "500ms" }""")
                AlertGovernanceRules.sloViolations(root, TEST_SLOS).single().detail shouldContain "api-availability"
            }

            test("カタログの遅延が変わると、ルールの境界との違いを検出する(s の単位も読む)") {
                val root = sloWorkspace("""slo: { availability: "99.95", latencyP99: "1s" }""")
                AlertGovernanceRules.sloViolations(root, TEST_SLOS).single().detail shouldContain "api-latency"
            }

            test("カタログに SLO がなければ違反にする") {
                val root = sloWorkspace("tier: 1")
                AlertGovernanceRules.sloViolations(root, TEST_SLOS) shouldHaveSize 2
            }
        }
    })
