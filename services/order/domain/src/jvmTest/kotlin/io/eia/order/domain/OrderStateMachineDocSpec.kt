package io.eia.order.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** docs/architecture/order-state-machine.md の遷移表と、domain の遷移表([OrderStatus.allowedTransitions])を照合する。 */
class OrderStateMachineDocSpec :
    FunSpec({
        test("ドキュメントの遷移表と OrderStatus.allowedTransitions が一致する") {
            val doc = File("../../../docs/architecture/order-state-machine.md").readText()
            val table = doc.substringAfter("## 遷移表").substringBefore("\n## ")
            val documented =
                Regex("""^\| `([A-Z]+)` \| `([A-Z]+)` \|""", RegexOption.MULTILINE)
                    .findAll(table)
                    .map { OrderStatus.valueOf(it.groupValues[1]) to OrderStatus.valueOf(it.groupValues[2]) }
                    .toSet()
            documented shouldBe OrderStatus.allowedTransitions.flatMap { (from, to) -> to.map { from to it } }.toSet()
        }

        test("ドキュメントに NoOp のときはイベントを発行しないことと、楽観的ロックでの判定のし直しが書かれている") {
            val doc = File("../../../docs/architecture/order-state-machine.md").readText()
            doc.contains("NoOp のときは、状態変更のイベントを発行しない") shouldBe true
            doc.contains("失敗した側は最新の状態を読み直し、この遷移表で判定し直す") shouldBe true
        }
    })
