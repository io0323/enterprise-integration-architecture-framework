package io.eia.tools.contract

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

private const val EVENTS = "contracts/catalog/INT-SALES-002.yaml"
private const val EVENTS_CONTRACT = "contracts/asyncapi/order-events.v1.yaml"
private const val ORDER_CANCELLED = "contracts/avro/sales/OrderCancelled.avsc"
private const val FILE = "contracts/catalog/INT-SALES-004.yaml"
private const val FILE_CONTRACT = "contracts/files/sales_daily.v1.yaml"
private const val ACTIVE = "lifecycle: active"
private const val DESIGN = "lifecycle: design"
private val TODAY: LocalDate = LocalDate.parse("2026-10-20")

/** deprecated の行(移行の期限と通知)。 */
private fun deprecated(sunset: String = "2026-11-30") = "lifecycle: deprecated\ndeprecation: { sunset: $sunset, notice: \"#22\" }"

/** retired の行(移行の期限は過ぎている)。 */
private const val RETIRED = "lifecycle: retired\ndeprecation: { sunset: 2026-09-30, notice: \"#22\" }\nretirement: { issue: \"#22\" }"

private fun Workspace.lifecycle(
    path: String,
    from: String,
    to: String,
) = replace(path, from, to)

/** 連携のライフサイクルの検査(ADR-0034)。実際の contracts/ を比較元(main)にして、状態を変えたものを検査する。 */
class LifecycleSpec :
    FunSpec({
        fun check(
            baseline: Workspace,
            current: Workspace,
            today: LocalDate = TODAY,
        ) = current.check(baseline = baseline, today = today)

        fun ids(result: CheckResult) = result.violations.map { it.rule.id }.toSet()

        test("状態を 1 段階ずつ進めるのは違反にならない(design → active・active → deprecated)") {
            val base = Workspace.ofRepositoryContracts()
            ids(check(base, Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, ACTIVE))).shouldBeEmpty()
            ids(check(base, Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, deprecated()))).shouldBeEmpty()
        }

        test("CC-LIFECYCLE-001: 飛ばす・2 段階以上進める・戻すのは違反") {
            val base = Workspace.ofRepositoryContracts()
            withClue("active → retired(deprecated を飛ばす)") {
                ids(check(base, Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, RETIRED))) shouldBe setOf("CC-LIFECYCLE-001")
            }
            withClue("design → deprecated(2 段階)") {
                ids(check(base, Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, deprecated()))) shouldBe setOf("CC-LIFECYCLE-001")
            }
            withClue("active → design(戻す)") {
                ids(check(base, Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, DESIGN))) shouldBe setOf("CC-LIFECYCLE-001")
            }
        }

        test("廃止の取り消し(deprecated → active)は、reactivation に reason と approver があるときだけ許す") {
            val base = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, deprecated())
            withClue("reactivation がない") {
                ids(check(base, Workspace.ofRepositoryContracts())) shouldBe setOf("CC-LIFECYCLE-001")
            }
            withClue("approver がない(スキーマの必須項目でもある)") {
                val current = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, "$ACTIVE\nreactivation: { reason: 移行先の廃止が中止された }")
                ids(check(base, current)) shouldBe setOf("CC-LIFECYCLE-001", "CC-CATALOG-001")
            }
            withClue("reason と approver がある") {
                val current =
                    Workspace
                        .ofRepositoryContracts()
                        .lifecycle(EVENTS, ACTIVE, "$ACTIVE\nreactivation: { reason: 移行先の廃止が中止された, approver: \"@io0323\" }")
                ids(check(base, current)).shouldBeEmpty()
            }
        }

        test("新しい連携は proposed か design で登録する。既存の連携の後からの登録(onboarding)だけ active で始めてよい") {
            val base = Workspace.ofRepositoryContracts().delete(FILE)
            ids(check(base, Workspace.ofRepositoryContracts())).shouldBeEmpty()
            withClue("active で登録(onboarding なし)") {
                ids(check(base, Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, ACTIVE))) shouldBe setOf("CC-LIFECYCLE-001")
            }
            withClue("active で登録(onboarding に reason がない。スキーマの必須項目でもある)") {
                val current = Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, "$ACTIVE\nonboarding: { kind: existing }")
                ids(check(base, current)) shouldBe setOf("CC-LIFECYCLE-001", "CC-CATALOG-001")
            }
            withClue("active で登録(既存の連携の後からの登録)") {
                val current =
                    Workspace.ofRepositoryContracts().lifecycle(
                        FILE,
                        DESIGN,
                        "$ACTIVE\nonboarding: { kind: existing, reason: 2024 年から動いている }",
                    )
                ids(check(base, current)).shouldBeEmpty()
            }
            withClue("deprecated で登録") {
                ids(check(base, Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, deprecated()))) shouldBe setOf("CC-LIFECYCLE-001")
            }
        }

        test("CC-LIFECYCLE-004: retired に進めるのは移行の期限の後だけ") {
            val base = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, deprecated(sunset = "2026-11-30"))
            val current = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, RETIRED)
            ids(check(base, current, today = LocalDate.parse("2026-11-29"))) shouldBe setOf("CC-LIFECYCLE-004")
            ids(check(base, current, today = LocalDate.parse("2026-11-30"))).shouldBeEmpty()
        }

        test("CC-LIFECYCLE-002: retired でないエントリは消せない。retired のものは、契約のファイルと一緒に消してよい") {
            val base = Workspace.ofRepositoryContracts()
            withClue("active のエントリだけを消す(契約は登録されていない状態になる)") {
                ids(check(base, Workspace.ofRepositoryContracts().delete(EVENTS))) shouldBe setOf("CC-LIFECYCLE-002", "CC-CATALOG-006")
            }
            val retiredBase = Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, RETIRED)
            ids(check(retiredBase, Workspace.ofRepositoryContracts().delete(FILE).delete(FILE_CONTRACT))).shouldBeEmpty()
        }

        test("CC-LIFECYCLE-003: retired でない連携が使う契約のファイルと、AsyncAPI が参照する Avro は消せない") {
            val base = Workspace.ofRepositoryContracts()
            withClue("エントリと AsyncAPI を同時に消す(今までの検査では検出できなかった)") {
                val result = check(base, Workspace.ofRepositoryContracts().delete(EVENTS).delete(EVENTS_CONTRACT))
                ids(result) shouldBe setOf("CC-LIFECYCLE-002", "CC-LIFECYCLE-003")
                result.violations.single { it.rule == Rule.LIFECYCLE_CONTRACT_REMOVED }.file shouldBe EVENTS_CONTRACT
            }
            withClue("AsyncAPI が参照する Avro を消す") {
                val result = check(base, Workspace.ofRepositoryContracts().delete(ORDER_CANCELLED))
                result.violations.filter { it.rule == Rule.LIFECYCLE_CONTRACT_REMOVED }.map { it.file } shouldBe listOf(ORDER_CANCELLED)
            }
            withClue("ファイルの仕様を消す") {
                val result = check(base, Workspace.ofRepositoryContracts().delete(FILE_CONTRACT))
                result.violations.filter { it.rule == Rule.LIFECYCLE_CONTRACT_REMOVED }.map { it.file } shouldBe listOf(FILE_CONTRACT)
            }
        }

        test("状態ごとの項目: deprecated は deprecation、retired は retirement が要る(CC-CATALOG-001)") {
            ids(Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, "lifecycle: deprecated").check()) shouldBe
                setOf("CC-CATALOG-001")
            ids(Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, deprecated(sunset = "2026/11/30")).check()) shouldBe
                setOf("CC-CATALOG-001")
            val noRetirement = "lifecycle: retired\ndeprecation: { sunset: 2026-09-30, notice: \"#22\" }"
            ids(Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, noRetirement).check()) shouldBe setOf("CC-CATALOG-001")
        }

        test("CC-LIFECYCLE-005: 移行の期限を過ぎた deprecated は警告(違反ではない。比較元がなくても出す。CLI は 0 で終わる)") {
            val zombie = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, deprecated(sunset = "2026-10-19"))
            val result = zombie.check(today = TODAY)
            result.violations.shouldBeEmpty()
            result.warnings.map { it.rule.id to it.file } shouldBe listOf("CC-LIFECYCLE-005" to EVENTS)
            Report.text(result) shouldContain "警告が 1 件あります(ジョブは失敗させません)"
            Report.markdown(result) shouldContain "### 警告(ジョブは失敗させません)"
            Report.markdown(result) shouldContain "| `$EVENTS` | `CC-LIFECYCLE-005` |"

            // 期限の当日は、まだ警告しない
            Workspace
                .ofRepositoryContracts()
                .lifecycle(EVENTS, ACTIVE, deprecated(sunset = "2026-10-20"))
                .check(today = TODAY)
                .warnings
                .shouldBeEmpty()

            val markdown = createTempDirectory("lifecycle").resolve("summary.md")
            // CLI は今日(UTC)で判定するので、期限は十分に前にする
            val longAgo = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, deprecated(sunset = "2026-01-31"))
            runCli(listOf("--root", longAgo.root.toString(), "--markdown", markdown.toString())) shouldBe 0
            markdown.readText() shouldContain "CC-LIFECYCLE-005"
        }

        test("互換性と状態の連動: 比較元で設計中の連携だけが使う契約は、破壊的変更を許可して報告する。active なら違反") {
            val breaking = { workspace: Workspace -> workspace.replace(FILE_CONTRACT, "    type: int64", "    type: string") }
            val designResult = check(Workspace.ofRepositoryContracts(), breaking(Workspace.ofRepositoryContracts()))
            designResult.violations.shouldBeEmpty()
            designResult.designAllowed.map { it.rule.id to it.file } shouldBe listOf("CC-COMPAT-003" to FILE_CONTRACT)
            Report.text(designResult) shouldContain "設計中の連携の契約なので許可した互換性の違反が 1 件あります"
            Report.markdown(designResult) shouldContain "### 設計中の連携の契約なので許可した互換性の違反"

            val activeBase = Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, ACTIVE)
            val activeResult = check(activeBase, breaking(Workspace.ofRepositoryContracts().lifecycle(FILE, DESIGN, ACTIVE)))
            ids(activeResult) shouldBe setOf("CC-COMPAT-003")
            activeResult.designAllowed.shouldBeEmpty()

            withClue("比較元の状態で決める(同じ PR で design に戻しても許可しない)") {
                ids(check(activeBase, breaking(Workspace.ofRepositoryContracts()))) shouldBe setOf("CC-COMPAT-003", "CC-LIFECYCLE-001")
            }
        }

        test("比較元がなければ、状態の遷移の検査は省略する") {
            val result = Workspace.ofRepositoryContracts().lifecycle(EVENTS, ACTIVE, RETIRED).check()
            result.baselineUsed shouldBe false
            ids(result).shouldBeEmpty()
        }
    })
