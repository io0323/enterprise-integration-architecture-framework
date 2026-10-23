package io.eia.tools.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val EXTERNAL_API = "contracts/external/crm-api.v1.yaml"
private const val WEBHOOK = "contracts/openapi/crm-webhook.v1.yaml"

/** 外部の契約(contracts/external。ADR-0035 §8)の扱い。ベンダーの破壊的変更は止められないので、違反ではなく警告にする。 */
class ExternalContractSpec :
    FunSpec({
        test("外部の契約の破壊的変更は CC-EXTERNAL-001 の警告(違反ではない。Step Summary に出る)") {
            val current =
                Workspace
                    .ofRepositoryContracts()
                    .replace(EXTERNAL_API, "        version:\n          type: integer\n", "        version:\n          type: string\n")
            val result = current.check(baseline = Workspace.ofRepositoryContracts())

            result.violations.shouldBeEmpty()
            result.designAllowed.shouldBeEmpty()
            val warnings = result.warnings.filter { it.rule == Rule.EXTERNAL_BREAKING }
            warnings.shouldNotBeEmpty()
            warnings.map { it.file }.toSet() shouldBe setOf(EXTERNAL_API)
            Report.markdown(result) shouldContain "`CC-EXTERNAL-001`"
        }

        test("自社の契約(Webhook の受け口)の破壊的変更は、設計中なら許可・active なら違反(外部の扱いにはしない)") {
            val breaking = { ws: Workspace -> ws.replace(WEBHOOK, "        maxLength: 128\n", "        maxLength: 64\n") }
            // 状態はリポジトリの値に頼らず明示する(INT-SALES-005 は P10 の後に active にした)
            val designBase = Workspace.ofRepositoryContracts().setLifecycle("contracts/catalog/INT-SALES-005.yaml", "lifecycle: design")
            val design =
                breaking(Workspace.ofRepositoryContracts().setLifecycle("contracts/catalog/INT-SALES-005.yaml", "lifecycle: design"))
                    .check(baseline = designBase)
            design.violations.shouldBeEmpty()
            design.warnings.filter { it.rule == Rule.EXTERNAL_BREAKING }.shouldBeEmpty()
            design.designAllowed.map { it.rule.id }.toSet() shouldBe setOf("CC-COMPAT-002")

            val activeBase = Workspace.ofRepositoryContracts().setLifecycle("contracts/catalog/INT-SALES-005.yaml", "lifecycle: active")
            val active =
                breaking(Workspace.ofRepositoryContracts().setLifecycle("contracts/catalog/INT-SALES-005.yaml", "lifecycle: active"))
                    .check(baseline = activeBase)
            active.violations.map { it.rule.id }.toSet() shouldBe setOf("CC-COMPAT-002")
        }
    })
