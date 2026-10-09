package io.eia.inventory.application

import io.eia.inventory.application.port.inbound.CommandEnvelope
import io.eia.inventory.application.port.inbound.CommandOutcome
import io.eia.inventory.application.port.inbound.PurgedRecords
import io.eia.inventory.application.port.outbound.InventoryReplies
import io.eia.inventory.application.port.outbound.ProcessedCommands
import io.eia.inventory.application.port.outbound.ReservationStore
import io.eia.inventory.application.port.outbound.StockLedger
import io.eia.inventory.application.port.outbound.TransactionRunner
import io.eia.inventory.application.usecase.PurgeExpiredRecordsService
import io.eia.inventory.application.usecase.ReleaseStockService
import io.eia.inventory.application.usecase.ReserveStockService
import io.eia.inventory.domain.RejectionReason
import io.eia.inventory.domain.ReleaseOutcome
import io.eia.inventory.domain.Reservation
import io.eia.inventory.domain.ReservationStatus
import io.eia.inventory.domain.ReserveReply
import io.eia.inventory.domain.SettledRetention
import io.eia.inventory.domain.StockLevel
import io.eia.inventory.domain.StockLine
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

/** メモリの中の状態。トランザクションは、Err のときに始める前の状態へ戻す。 */
private class World(
    stock: Map<String, Long>,
) {
    val processed = mutableSetOf<String>()
    val reservations = mutableMapOf<String, Reservation>()
    val levels = stock.mapValues { (sku, onHand) -> StockLevel(sku, onHand, 0) }.toMutableMap()
    val replies = mutableListOf<String>()
    var lockedSkus = mutableListOf<List<String>>()
    var failReplies = false
    var purgeBatches = mutableListOf(0)
    var processedPurges = mutableListOf(0)

    val transactions =
        object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
                val snapshot = Triple(processed.toSet(), reservations.toMap(), levels.toMap())
                val repliesBefore = replies.size
                val result = block()
                if (result is Result.Err) {
                    processed.clear()
                    processed += snapshot.first
                    reservations.clear()
                    reservations += snapshot.second
                    levels.clear()
                    levels += snapshot.third
                    while (replies.size > repliesBefore) replies.removeLast()
                }
                return result
            }
        }
    val processedCommands =
        object : ProcessedCommands {
            override suspend fun markProcessed(
                messageId: String,
                topic: String,
            ): Result<Boolean, DomainError> = ok(processed.add(messageId))

            override suspend fun purgeExpired(batchSize: Int): Result<Int, DomainError> = ok(processedPurges.removeFirstOrNull() ?: 0)
        }
    val store =
        object : ReservationStore {
            override suspend fun findForUpdate(sagaId: String): Result<Reservation?, DomainError> = ok(reservations[sagaId])

            override suspend fun insert(reservation: Reservation): Result<Unit, DomainError> {
                check(reservation.sagaId !in reservations) { "同じ Saga の行を 2 回書いた" }
                reservations[reservation.sagaId] = reservation
                return ok(Unit)
            }

            override suspend fun markReleased(sagaId: String): Result<Unit, DomainError> {
                reservations[sagaId] = reservations.getValue(sagaId).copy(status = ReservationStatus.RELEASED)
                return ok(Unit)
            }

            override suspend fun purgeSettled(
                retention: Duration,
                batchSize: Int,
            ): Result<Int, DomainError> = ok(purgeBatches.removeFirstOrNull() ?: 0)
        }
    val ledger =
        object : StockLedger {
            override suspend fun lock(skus: Set<String>): Result<Map<String, StockLevel>, DomainError> {
                lockedSkus += skus.sorted()
                return ok(levels.filterKeys { it in skus })
            }

            override suspend fun adjustReserved(deltas: Map<String, Long>): Result<Unit, DomainError> {
                deltas.forEach { (sku, delta) ->
                    val level = levels.getValue(sku)
                    check(level.reserved + delta in 0..level.onHand) { "在庫が負になった" }
                    levels[sku] = level.copy(reserved = level.reserved + delta)
                }
                return ok(Unit)
            }
        }
    val inventoryReplies =
        object : InventoryReplies {
            override suspend fun reserveReplied(
                sagaId: String,
                orderId: String,
                reply: ReserveReply,
            ): Result<Unit, DomainError> = record("$sagaId:$reply")

            override suspend fun released(
                sagaId: String,
                orderId: String,
                outcome: ReleaseOutcome,
            ): Result<Unit, DomainError> = record("$sagaId:$outcome")

            fun record(reply: String): Result<Unit, DomainError> =
                if (failReplies) {
                    err(UnavailableError("Outbox に書けない"))
                } else {
                    replies += reply
                    ok(Unit)
                }
        }

    val reserve = ReserveStockService(transactions, processedCommands, store, ledger, inventoryReplies)
    val release = ReleaseStockService(transactions, processedCommands, store, ledger, inventoryReplies)
}

private fun envelope(
    id: String,
    saga: String = "saga-1",
) = CommandEnvelope(id, "inventory.stock.cmd-reserve.v1", saga, "order-1")

private fun lines(vararg quantities: Pair<String, Long>) =
    quantities.mapIndexed { index, (sku, quantity) -> StockLine.of(index + 1, sku, quantity).ok() }

class InventoryServicesSpec :
    FunSpec({
        test("引き当てて返事を書く。同じ ce_id の 2 回目は DUPLICATE で何もしない") {
            val world = World(mapOf("SKU-1" to 10L))
            world.reserve(envelope("m-1"), lines("SKU-1" to 4L)).ok() shouldBe CommandOutcome.PROCESSED
            world.reserve(envelope("m-1"), lines("SKU-1" to 4L)).ok() shouldBe CommandOutcome.DUPLICATE

            world.levels.getValue("SKU-1").reserved shouldBe 4
            world.replies shouldBe listOf("saga-1:Reserved")
        }

        test("同じ Saga の別の ce_id(Orchestrator の送り直し)には、在庫を変えずに同じ返事を返し直す。在庫のロックも取らない") {
            val world = World(mapOf("SKU-1" to 10L))
            world.reserve(envelope("m-1"), lines("SKU-1" to 4L)).ok()
            world.reserve(envelope("m-2"), lines("SKU-1" to 4L)).ok() shouldBe CommandOutcome.PROCESSED

            world.levels.getValue("SKU-1").reserved shouldBe 4
            world.replies shouldBe listOf("saga-1:Reserved", "saga-1:Reserved")
            world.lockedSkus shouldBe listOf(listOf("SKU-1"), emptyList())
        }

        test("在庫不足は拒否を記録して返事を書き、在庫は変えない。在庫は SKU の順にロックする") {
            val world = World(mapOf("SKU-2" to 1L, "SKU-1" to 10L))
            world.reserve(envelope("m-1"), lines("SKU-2" to 2L, "SKU-1" to 1L)).ok()

            world.lockedSkus.single() shouldBe listOf("SKU-1", "SKU-2")
            world.levels.values.sumOf { it.reserved } shouldBe 0
            world.reservations.getValue("saga-1").status shouldBe ReservationStatus.REJECTED
            world.replies shouldBe listOf("saga-1:${ReserveReply.Rejected(RejectionReason.INSUFFICIENT_STOCK)}")
        }

        test("解放: 在庫を戻して返事を書く。2 回目以降は在庫を変えずに RELEASED を返し直す") {
            val world = World(mapOf("SKU-1" to 10L))
            world.reserve(envelope("m-1"), lines("SKU-1" to 4L)).ok()
            world.release(envelope("m-2")).ok() shouldBe CommandOutcome.PROCESSED
            world.release(envelope("m-3")).ok() shouldBe CommandOutcome.PROCESSED

            world.levels.getValue("SKU-1").reserved shouldBe 0
            world.reservations.getValue("saga-1").status shouldBe ReservationStatus.RELEASED
            world.replies.drop(1) shouldBe listOf("saga-1:RELEASED", "saga-1:RELEASED")
        }

        test("解放が先に届けば印を作り(NOT_RESERVED)、後から届いた引当の指示を ALREADY_RELEASED で拒否する(在庫は変えない)") {
            val world = World(mapOf("SKU-1" to 10L))
            world.release(envelope("m-1")).ok()
            world.reserve(envelope("m-2"), lines("SKU-1" to 4L)).ok()

            world.reservations.getValue("saga-1").status shouldBe ReservationStatus.RELEASED_BEFORE_RESERVATION
            world.levels.getValue("SKU-1").reserved shouldBe 0
            world.replies shouldBe listOf("saga-1:NOT_RESERVED", "saga-1:${ReserveReply.Rejected(RejectionReason.ALREADY_RELEASED)}")
        }

        test("返事を書けなければ、引当・在庫・冪等消費の記録も取り消す(次の受信でやり直せる)") {
            val world = World(mapOf("SKU-1" to 10L))
            world.failReplies = true
            world.reserve(envelope("m-1"), lines("SKU-1" to 4L)).shouldBeInstanceOf<Result.Err<DomainError>>()

            world.processed shouldBe emptySet()
            world.reservations shouldBe emptyMap()
            world.levels.getValue("SKU-1").reserved shouldBe 0

            world.failReplies = false
            world.reserve(envelope("m-1"), lines("SKU-1" to 4L)).ok() shouldBe CommandOutcome.PROCESSED
        }

        test("Saga ID と注文 ID の誤りは、トランザクションの前に ValidationError(DLQ に送られる)") {
            val world = World(mapOf("SKU-1" to 10L))
            (world.reserve(CommandEnvelope("m-1", "t", "", "order-1"), lines("SKU-1" to 1L)) as Result.Err)
                .error
                .shouldBeInstanceOf<ValidationError>()
            (world.release(CommandEnvelope("m-1", "t", "saga-1", "")) as Result.Err).error.shouldBeInstanceOf<ValidationError>()
            world.processed shouldBe emptySet()
        }

        test("保持期間を過ぎた記録を、上限より少なく消えるまで繰り返し消す") {
            val world = World(emptyMap())
            world.purgeBatches = mutableListOf(2, 2, 1)
            world.processedPurges = mutableListOf(2, 0)
            PurgeExpiredRecordsService(world.store, world.processedCommands, SettledRetention.of(20.days).ok(), batchSize = 2)
                .invoke()
                .ok() shouldBe PurgedRecords(reservations = 5, processedMessages = 2)
        }
    })
