package io.eia.order.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** docs/architecture/order-saga.md の遷移表・状態遷移図と、domain の遷移表([OrderSagaRules.transitions])を照合する。 */
class OrderSagaDocSpec :
    FunSpec({
        val doc = File("../../../docs/architecture/order-saga.md").readText()

        test("ドキュメントの遷移表と OrderSagaRules.transitions が一致する(遷移先・注文の状態・補償の理由を含む)") {
            val table = doc.substringAfter("## 遷移表").substringBefore("\n## ")
            val documented =
                Regex("""^\| `([A-Z_]+)` \| `([A-Z_]+)` \| `([A-Z_]+)` \| (`[A-Z_]+`|—) \| (`[A-Z_]+`|—) \|""", RegexOption.MULTILINE)
                    .findAll(table)
                    .map { m ->
                        fun optional(cell: String) = cell.trim('`').takeIf { it != "—" }
                        SagaTransition(
                            SagaState.valueOf(m.groupValues[1]),
                            SagaSignal.valueOf(m.groupValues[2]),
                            SagaState.valueOf(m.groupValues[3]),
                            optional(m.groupValues[4])?.let(OrderStatus::valueOf),
                            optional(m.groupValues[5])?.let(SagaFailure::valueOf),
                        )
                    }.toSet()
            documented shouldBe OrderSagaRules.transitions.toSet()
        }

        test("状態遷移図(Mermaid)の遷移も、遷移表と補償の送り直しに一致する") {
            val diagram = doc.substringAfter("```mermaid").substringBefore("```")
            val arrows =
                Regex("""^\s*([A-Z_]+) --> ([A-Z_]+): ([A-Z_]+)""", RegexOption.MULTILINE)
                    .findAll(diagram)
                    .map {
                        Triple(
                            SagaState.valueOf(it.groupValues[1]),
                            SagaSignal.valueOf(it.groupValues[3]),
                            SagaState.valueOf(it.groupValues[2]),
                        )
                    }.toSet()
            val resends = SagaState.entries.filter { it.isCompensating }.map { Triple(it, SagaSignal.STEP_TIMED_OUT, it) }
            arrows shouldBe OrderSagaRules.transitions.map { Triple(it.from, it.signal, it.to) }.toSet() + resends
        }

        test("ドキュメントに、補償を逆の順に 1 つずつ行うこと・期限は DB の時計で判定すること・表にない結果は無視することが書かれている") {
            doc.contains("前進の逆の順に、1 つずつ") shouldBe true
            doc.contains("**DB の時計**(`clock_timestamp()`)") shouldBe true
            doc.contains("は **無視する**") shouldBe true
        }
    })
