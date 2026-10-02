package io.eia.platform.messagingkafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EventTopicSpec :
    FunSpec({
        test("{domain}.{entity}.{event}.v{n} を受け付け、ce_type はバージョンを除いた名前") {
            EventTopic.of("sales.order.created.v1").ceType shouldBe "sales.order.created"
            EventTopic.of("sales.legacy-order.changed.v12").ceType shouldBe "sales.legacy-order.changed"
            EventTopic.of("inventory.stock.cmd-reserve.v1").ceType shouldBe "inventory.stock.cmd-reserve"
        }

        test("命名規約に合わない名前を拒否する(contract-check の Naming と同じ規則)") {
            listOf(
                "sales.order.created",
                "sales.order.created.v0",
                "Sales.order.created.v1",
                "sales.order_line.created.v1",
                "sales.order.created.v1.dlq",
                "sales.order.cmdreserve.v1",
                "_connect.offsets",
            ).forEach { name ->
                EventTopic.parse(name).err()
                shouldThrow<IllegalArgumentException> { EventTopic.of(name) }
            }
        }
    })
