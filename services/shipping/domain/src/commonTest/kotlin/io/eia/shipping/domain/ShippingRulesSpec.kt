package io.eia.shipping.domain

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-09T01:00:00Z")
private val RULES = ShippingRules()
private val JP = Destination("JP")

class ShippingRulesSpec :
    FunSpec({
        test("出荷できる国(既定は JP)なら直ちに出荷し、出荷 ID と時刻を記録する") {
            val decision =
                RULES
                    .arrange(
                        null,
                        "saga-1",
                        "order-1",
                        JP,
                    ) { ShipmentStamp("ship-1", NOW) }
                    .shouldBeInstanceOf<ArrangeDecision.Record>()
            decision.reply shouldBe ArrangeReply.Shipped("ship-1", NOW)
            decision.shipment shouldBe Shipment("saga-1", "order-1", ShipmentStatus.SHIPPED, "ship-1", NOW, destination = JP)
        }

        test("出荷できない国は UNSUPPORTED_DESTINATION で拒否を記録する(出荷 ID は採らない)") {
            val decision =
                RULES
                    .arrange(
                        null,
                        "s",
                        "o",
                        Destination("US"),
                    ) { error("採番しない") }
                    .shouldBeInstanceOf<ArrangeDecision.Record>()
            decision.reply shouldBe ArrangeReply.Rejected(RejectionReason.UNSUPPORTED_DESTINATION)
            decision.shipment.status shouldBe ShipmentStatus.REJECTED
            ShippingRules(
                setOf("JP", "US"),
            ).arrange(null, "s", "o", Destination("US")) { ShipmentStamp("x", NOW) }.reply.shouldBeInstanceOf<ArrangeReply.Shipped>()
        }

        test("同じ Saga の 2 回目以降は、記録から返事を返し直す(印は ALREADY_CANCELLED)") {
            fun replay(existing: Shipment) = RULES.arrange(existing, "s", "o", JP) { error("採番しない") }
            replay(Shipment("s", "o", ShipmentStatus.SHIPPED, "ship-1", NOW)) shouldBe
                ArrangeDecision.Replay(ArrangeReply.Shipped("ship-1", NOW))
            replay(Shipment("s", "o", ShipmentStatus.REJECTED, rejection = RejectionReason.UNSUPPORTED_DESTINATION)) shouldBe
                ArrangeDecision.Replay(ArrangeReply.Rejected(RejectionReason.UNSUPPORTED_DESTINATION))
            replay(Shipment("s", "o", ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT)) shouldBe
                ArrangeDecision.Replay(ArrangeReply.Rejected(RejectionReason.ALREADY_CANCELLED))
        }

        test("取消: 記録がなければ印を作る(NOT_ARRANGED)。出荷済みなら取り消さない(ALREADY_SHIPPED)。拒否・印なら NOT_ARRANGED") {
            val marked = RULES.cancel(null, "s", "o").shouldBeInstanceOf<CancelDecision.MarkCancelledBeforeArrangement>()
            marked.marker shouldBe Shipment("s", "o", ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT)
            marked.outcome shouldBe CancelOutcome.NOT_ARRANGED
            RULES.cancel(Shipment("s", "o", ShipmentStatus.SHIPPED, "ship-1", NOW), "s", "o") shouldBe
                CancelDecision.Replay(CancelOutcome.ALREADY_SHIPPED)
            RULES.cancel(Shipment("s", "o", ShipmentStatus.REJECTED), "s", "o") shouldBe CancelDecision.Replay(CancelOutcome.NOT_ARRANGED)
            RULES.cancel(Shipment("s", "o", ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT), "s", "o") shouldBe
                CancelDecision.Replay(CancelOutcome.NOT_ARRANGED)
        }

        test("終わった状態は、出荷した記録(SHIPPED)以外") {
            ShipmentStatus.entries.filter { !it.isSettled } shouldBe listOf(ShipmentStatus.SHIPPED)
        }

        test("値の検査と既定値") {
            (Destination.of("jp") is Result.Err) shouldBe true
            (Destination.of("JP") as Result.Ok).value shouldBe JP
            (ShipmentLine.of(0, "", 0) as Result.Err).error.violations.map { it.field } shouldBe
                listOf("lines.lineNumber", "lines.sku", "lines.quantity")
            (ShipmentLine.of(1, "SKU-1", 1) as Result.Ok).value shouldBe ShipmentLine(1, "SKU-1", 1)
            (validateIdentifiers("sagaId" to " ") as Result.Err)
                .error.violations
                .single()
                .field shouldBe "sagaId"
            (validateIdentifiers("sagaId" to "s") is Result.Ok) shouldBe true
            SettledRetention.DEFAULT.value shouldBe 30.days
            (SettledRetention.of(13.days) is Result.Err) shouldBe true
            (SettledRetention.of(14.days) as Result.Ok).value.value shouldBe 14.days
        }
    })
