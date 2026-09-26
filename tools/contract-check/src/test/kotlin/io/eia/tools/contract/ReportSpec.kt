package io.eia.tools.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

class ReportSpec :
    FunSpec({
        test("ルール ID は CC-{区分}-{3 桁} で一意") {
            val ids = Rule.entries.map { it.id }
            ids.filterNot { Regex("^CC-[A-Z]+-[0-9]{3}$").matches(it) }.shouldBeEmpty()
            ids
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .shouldBeEmpty()
        }

        test("README のルール一覧は Rule の定義と一致する") {
            val readme = Workspace.REPOSITORY_ROOT.resolve("tools/contract-check/README.md").readText()
            val rows = Regex("^\\| `(CC-[A-Z]+-[0-9]{3})` \\| (.+) \\| (.+) \\| (.+) \\|$", RegexOption.MULTILINE).findAll(readme)
            val documented = rows.associate { it.groupValues[1] to it.groupValues.drop(2) }

            documented.keys shouldBe Rule.entries.map { it.id }.toSet()
            Rule.entries.forEach { rule ->
                documented.getValue(rule.id) shouldBe listOf(rule.title, rule.chapter, rule.fix)
            }
        }

        test("Markdown は 5 項目の表で、セル内の | と改行をエスケープする") {
            val result =
                CheckResult(
                    listOf(Violation("contracts/x.yaml", Rule.API_SECURITY, "a | b\nc")),
                    baselineUsed = false,
                )
            val markdown = Report.markdown(result)

            markdown shouldContain "| ファイル | ルール ID | 内容 | Framework 章 | 修正方法 |"
            markdown shouldContain "| `contracts/x.yaml` | `CC-API-003` | a \\| b<br>c | 12 | ${Rule.API_SECURITY.fix} |"
            markdown shouldContain "比較元がないため互換性検査 CC-COMPAT-* は省略"
            Report.text(result) shouldContain "Framework 章 : 12"
        }

        test("CLI: 違反がなければ 0、あれば 1、引数が不正なら 2 を返し、Markdown を書き出す") {
            val clean = Workspace.ofRepositoryContracts()
            val markdown = createTempDirectory("report").resolve("out/summary.md")
            runCli(listOf("--root", clean.root.toString(), "--markdown", markdown.toString())) shouldBe 0
            markdown.readText() shouldContain "違反はありません"

            val broken = Workspace.ofRepositoryContracts().replace("contracts/catalog/INT-SALES-001.yaml", "tier: 1\n", "")
            runCli(listOf("--root", broken.root.toString(), "--markdown", markdown.toString())) shouldBe 1
            markdown.readText() shouldContain "`CC-CATALOG-001`"

            val baseline = Workspace.ofRepositoryContracts()
            val args = listOf("--root", clean.root.toString(), "--baseline", baseline.root.toString())
            runCli(args + listOf("--oasdiff", Workspace.OASDIFF.toString())) shouldBe 0
            runCli(listOf("--root")) shouldBe 2
            runCli(listOf("--unknown", "x")) shouldBe 2
            Report.markdown(Workspace.ofRepositoryContracts().check()) shouldNotContain "| ファイル |"
        }
    })
