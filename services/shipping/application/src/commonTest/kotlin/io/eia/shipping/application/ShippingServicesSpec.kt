package io.eia.shipping.application

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.inbound.CommandOutcome
import io.eia.shipping.application.port.inbound.PurgedRecords
import io.eia.shipping.application.port.outbound.ProcessedCommands
import io.eia.shipping.application.port.outbound.ShipmentStore
import io.eia.shipping.application.port.outbound.ShippingReplies
import io.eia.shipping.application.port.outbound.TransactionRunner
import io.eia.shipping.application.usecase.ArrangeShipmentService
import io.eia.shipping.application.usecase.CancelShipmentService
import io.eia.shipping.application.usecase.PurgeExpiredRecordsService
import io.eia.shipping.domain.ArrangeReply
import io.eia.shipping.domain.CancelOutcome
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.RejectionReason
import io.eia.shipping.domain.SettledRetention
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentLine
import io.eia.shipping.domain.ShipmentStamp
import io.eia.shipping.domain.ShipmentStatus
import io.eia.shipping.domain.ShippingRules
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val NOW = Instant.parse("2026-10-09T01:00:00Z")
private val LINES = listOf(ShipmentLine(1, "SKU-1", 1))

/** メモリの中の状態。トランザクションは、Err のときに始める前の状態へ戻す。 */
private class World {
    val processed = mutableSetOf<String>()
    val records = mutableMapOf<String, Shipment>()
    val replies = mutableListOf<String>()
    var failReplies = false
    var purgeBatches = mutableListOf(0)
    private var ids = 0

    val transactions =
        object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
                val snapshot = processed.toSet() to records.toMap()
                val before = replies.size
                return block().also { result ->
                    if (result is Result.Err) {
                        processed.clear()
                        processed += snapshot.first
                        records.clear()
                        records += snapshot.second
                        while (replies.size > before) replies.removeLast()
                    }
                }
            }
        }
    val processedCommands =
        object : ProcessedCommands {
            override suspend fun markProcessed(
                messageId: String,
                topic: String,
            ): Result<Boolean, DomainError> = ok(processed.add(messageId))

            override suspend fun purgeExpired(batchSize: Int): Result<Int, DomainError> = ok(0)
        }
    val store =
        object : ShipmentStore {
            override suspend fun findForUpdate(sagaId: String): Result<Shipment?, DomainError> = ok(records[sagaId])

            override suspend fun insert(shipment: Shipment): Result<Unit, DomainError> {
                check(shipment.sagaId !in records) { "同じ Saga の行を 2 回書いた" }
                records[shipment.sagaId] = shipment
                return ok(Unit)
            }

            override suspend fun purgeSettled(
                retention: Duration,
                batchSize: Int,
            ): Result<Int, DomainError> = ok(purgeBatches.removeFirstOrNull() ?: 0)
        }
    val shippingReplies =
        object : ShippingReplies {
            override suspend fun arrangeReplied(
                sagaId: String,
                orderId: String,
                reply: ArrangeReply,
            ): Result<Unit, DomainError> = record("$sagaId:$reply")

            override suspend fun cancelled(
                sagaId: String,
                orderId: String,
                outcome: CancelOutcome,
            ): Result<Unit, DomainError> = record("$sagaId:$outcome")

            fun record(reply: String): Result<Unit, DomainError> =
                if (failReplies) {
                    err(UnavailableError("Outbox に書けない"))
                } else {
                    replies += reply
                    ok(Unit)
                }
        }
    val arrange =
        ArrangeShipmentService(
            transactions,
            processedCommands,
            store,
            shippingReplies,
            ShippingRules(),
        ) { ShipmentStamp("ship-${++ids}", NOW) }
    val cancel = CancelShipmentService(transactions, processedCommands, store, shippingReplies, ShippingRules())
}

private fun envelope(id: String) = CommandEnvelope(id, "shipping.shipment.cmd-arrange.v1", "saga-1", "order-1")

class ShippingServicesSpec :
    FunSpec({
        test("出荷して返事を書く。同じ ce_id は DUPLICATE、同じ Saga の別の ce_id は同じ出荷 ID と時刻を返し直す") {
            val world = World()
            world.arrange(envelope("m-1"), Destination("JP"), LINES).ok() shouldBe CommandOutcome.PROCESSED
            world.arrange(envelope("m-1"), Destination("JP"), LINES).ok() shouldBe CommandOutcome.DUPLICATE
            world.arrange(envelope("m-2"), Destination("JP"), LINES).ok() shouldBe CommandOutcome.PROCESSED
            world.replies shouldBe List(2) { "saga-1:${ArrangeReply.Shipped("ship-1", NOW)}" }
        }

        test("出荷できない国は拒否を記録して UNSUPPORTED_DESTINATION を返す") {
            val world = World()
            world.arrange(envelope("m-1"), Destination("US"), LINES).ok()
            world.records.getValue("saga-1").status shouldBe ShipmentStatus.REJECTED
            world.replies shouldBe listOf("saga-1:${ArrangeReply.Rejected(RejectionReason.UNSUPPORTED_DESTINATION)}")
        }

        test("取消が先に届けば印を作り(NOT_ARRANGED)、後から届いた手配の指示を ALREADY_CANCELLED で拒否する") {
            val world = World()
            world.cancel(envelope("m-1")).ok()
            world.arrange(envelope("m-2"), Destination("JP"), LINES).ok()
            world.records.getValue("saga-1").status shouldBe ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT
            world.replies shouldBe listOf("saga-1:NOT_ARRANGED", "saga-1:${ArrangeReply.Rejected(RejectionReason.ALREADY_CANCELLED)}")
        }

        test("出荷した後の取消は ALREADY_SHIPPED(取り消さない)") {
            val world = World()
            world.arrange(envelope("m-1"), Destination("JP"), LINES).ok()
            world.cancel(envelope("m-2")).ok()
            world.records.getValue("saga-1").status shouldBe ShipmentStatus.SHIPPED
            world.replies.last() shouldBe "saga-1:ALREADY_SHIPPED"
        }

        test("返事を書けなければ、記録と冪等消費の記録も取り消す。明細が空・識別子の誤りはトランザクションの前に拒否する") {
            val world = World()
            world.failReplies = true
            world.arrange(envelope("m-1"), Destination("JP"), LINES).shouldBeInstanceOf<Result.Err<DomainError>>()
            world.processed shouldBe emptySet()
            world.records shouldBe emptyMap()
            (world.arrange(envelope("m-1"), Destination("JP"), emptyList()) as Result.Err).error.shouldBeInstanceOf<ValidationError>()
            (world.cancel(CommandEnvelope("m-1", "t", "", "o")) as Result.Err).error.shouldBeInstanceOf<ValidationError>()
        }

        test("保持期間を過ぎた記録を、上限より少なく消えるまで繰り返し消す") {
            val world = World()
            world.purgeBatches = mutableListOf(2, 2, 0)
            PurgeExpiredRecordsService(
                world.store,
                world.processedCommands,
                (SettledRetention.of(20.days) as Result.Ok).value,
                batchSize = 2,
            ).invoke()
                .ok() shouldBe PurgedRecords(records = 4, processedMessages = 0)
        }
    })
