package io.eia.tools.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

private fun nfrWorkspace(
    catalog: List<String>,
    rows: List<String>,
    results: List<String> = rows,
): File {
    val root = Files.createTempDirectory("nfr-report").toFile()
    root.resolve("contracts/catalog").mkdirs()
    catalog.forEach { root.resolve("contracts/catalog/$it.yaml").writeText("id: $it\nname: x\n") }
    root.resolve("docs/reports").mkdirs()
    root.resolve(NfrReportRules.REPORT).writeText(
        buildString {
            appendLine("# NFR\n\n<!-- nfr-table:begin -->")
            appendLine("| ID | 名前 |\n|---|---|")
            rows.forEach { appendLine("| $it | x |") }
            appendLine("<!-- nfr-table:end -->\n\n| INT-OTHER-001 | 表の外の行は数えない |")
        },
    )
    root.resolve(NfrReportRules.RESULTS).writeText(results.joinToString(",\n", "{\n", "\n}") { "\"$it\": { \"measured\": \"no\" }" })
    return root
}

class NfrReportSpec :
    FunSpec({
        val repository = File(checkNotNull(System.getProperty("eia.rootDir")))

        context("リポジトリの NFR のレポート(ROADMAP P14。ADR-0048)") {
            test("検査の対象がある(0 件で成功しない)") {
                NfrReportRules.catalogIds(repository).size shouldBeGreaterThan 20
            }

            test("カタログのすべての連携が、表と計測の結果に 1 回ずつある") {
                NfrReportRules.violations(repository).shouldBeEmpty()
            }
        }

        context("違反を検出する(一覧だけを比べ、数値は見ない)") {
            test("一覧が一致すれば違反にならない") {
                NfrReportRules.violations(nfrWorkspace(listOf("INT-A-001", "INT-B-001"), listOf("INT-A-001", "INT-B-001"))).shouldBeEmpty()
            }

            test("カタログに足した連携が表にない") {
                val violations = NfrReportRules.violations(nfrWorkspace(listOf("INT-A-001", "INT-B-001"), listOf("INT-A-001")))
                violations shouldHaveSize 2
                violations.first().detail shouldContain "INT-B-001"
            }

            test("カタログから消した連携が表に残っている") {
                NfrReportRules.violations(nfrWorkspace(listOf("INT-A-001"), listOf("INT-A-001", "INT-B-001"))) shouldHaveSize 2
            }

            test("同じ連携が表に 2 回ある") {
                val workspace = nfrWorkspace(listOf("INT-A-001"), listOf("INT-A-001", "INT-A-001"), listOf("INT-A-001"))
                NfrReportRules.violations(workspace) shouldHaveSize 1
            }

            test("計測の結果のファイルにない") {
                NfrReportRules.violations(nfrWorkspace(listOf("INT-A-001"), listOf("INT-A-001"), emptyList())) shouldHaveSize 1
            }
        }
    })
