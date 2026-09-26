package io.eia.tools.contract

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

private const val OPENAPI = "contracts/openapi/order-api.v1.yaml"
private val BEFORE_EXPIRY: LocalDate = LocalDate.parse("2026-10-01")

private const val REMOVED_PATH = "api-path-removed-without-deprecation"

/** 例外で許可する違反を含む作業ディレクトリ: パスの削除(CC-COMPAT-002)。比較元は変更前の contracts/。 */
private fun withRemovedPath(): Workspace =
    Workspace.ofRepositoryContracts().replace(OPENAPI, "  /v1/orders/{orderId}:\n", "  /v1/orders/{orderId}/renamed:\n")

private fun waiverFile(
    rule: String = "CC-COMPAT-002",
    contains: String = REMOVED_PATH,
    expires: String = "2026-10-31",
    extra: String = "",
): String =
    """
    waivers:
      - rule: $rule
        file: $OPENAPI
        contains: "$contains"
        reason: テスト
        issue: "#25"
        expires: $expires
    $extra
    """.trimIndent()

class WaiverSpec :
    FunSpec({
        test("例外がなければ、パスの削除は CC-COMPAT-002 で失敗する") {
            val result = withRemovedPath().check(Workspace.ofRepositoryContracts(), today = BEFORE_EXPIRY)

            withClue(result.violations.joinToString("\n")) { result.ruleIds shouldBe setOf("CC-COMPAT-002") }
        }

        test("期限内の例外に当たる違反は許可し、許可した違反として報告する") {
            val result = withRemovedPath().write(Waivers.FILE, waiverFile()).check(Workspace.ofRepositoryContracts(), today = BEFORE_EXPIRY)

            result.violations.shouldBeEmpty()
            result.waived shouldHaveSize 1
            result.waived
                .single()
                .violation.rule.id shouldBe "CC-COMPAT-002"
            Report.text(result) shouldContain "例外で許可した違反が 1 件あります"
            Report.markdown(result) shouldContain "| テスト | #25 | 2026-10-31 |"
        }

        test("contains に当たらない違反は許可しない") {
            val result =
                withRemovedPath()
                    .write(Waivers.FILE, waiverFile(contains = "api-security-scope-added"))
                    .check(Workspace.ofRepositoryContracts(), today = BEFORE_EXPIRY)

            result.ruleIds shouldBe setOf("CC-COMPAT-002")
            result.unusedWaivers shouldHaveSize 1
        }

        test("期限の当日までは許可し、翌日からは CC-WAIVER-002 で失敗して違反も許可しない") {
            val workspace = withRemovedPath().write(Waivers.FILE, waiverFile(expires = "2026-10-31"))
            val baseline = Workspace.ofRepositoryContracts()

            workspace.check(baseline, today = LocalDate.parse("2026-10-31")).violations.shouldBeEmpty()
            workspace.check(baseline, today = LocalDate.parse("2026-11-01")).ruleIds shouldBe
                setOf("CC-COMPAT-002", "CC-WAIVER-002")
        }

        test("比較元と一致して違反がなければ、使われていない例外として報告するが失敗にはしない") {
            val result =
                Workspace
                    .ofRepositoryContracts()
                    .write(Waivers.FILE, waiverFile())
                    .check(Workspace.ofRepositoryContracts(), today = BEFORE_EXPIRY)

            result.violations.shouldBeEmpty()
            result.unusedWaivers shouldHaveSize 1
            Report.text(result) shouldContain "使われていない例外があります"
        }

        mapOf(
            "CC-COMPAT-* 以外のルール" to waiverFile(rule = "CC-NAMING-001"),
            "存在しないルール" to waiverFile(rule = "CC-COMPAT-999"),
            "日付の形式が不正" to waiverFile(expires = "2026/10/31"),
            "必須項目の欠落" to waiverFile(extra = "  - rule: CC-COMPAT-002\n    file: $OPENAPI"),
            "waivers が配列でない" to "waivers: {}",
        ).forEach { (name, text) ->
            test("形式の違反: $name → CC-WAIVER-001") {
                val result = Workspace.ofRepositoryContracts().write(Waivers.FILE, text).check(today = BEFORE_EXPIRY)

                withClue(result.violations.joinToString("\n")) { result.ruleIds shouldBe setOf("CC-WAIVER-001") }
            }
        }
    })
