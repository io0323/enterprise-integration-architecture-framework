package io.eia.inventory.domain

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun line(
    n: Int,
    sku: String,
    quantity: Long,
) = StockLine.of(n, sku, quantity).ok()

private val STOCK =
    mapOf(
        "SKU-1" to StockLevel("SKU-1", onHand = 10, reserved = 4),
        "SKU-2" to StockLevel("SKU-2", onHand = 3, reserved = 0),
    )

class InventoryRulesSpec :
    FunSpec({
        test("全部の明細を引き当てられれば、SKU ごとに合計した数だけ引き当てる") {
            val lines = listOf(line(1, "SKU-1", 2), line(2, "SKU-2", 3), line(3, "SKU-1", 4))
            val decision = InventoryRules.reserve(null, "saga-1", "order-1", lines, STOCK).shouldBeInstanceOf<ReserveDecision.Reserve>()
            decision.increments shouldBe mapOf("SKU-1" to 6L, "SKU-2" to 3L)
            decision.reservation shouldBe Reservation("saga-1", "order-1", ReservationStatus.RESERVED, lines = lines)
            decision.reply shouldBe ReserveReply.Reserved
        }

        test("1 つでも足りなければ何も引き当てない(合計で判定する)。台帳にない SKU は在庫不足より優先") {
            InventoryRules
                .reserve(null, "saga-1", "order-1", listOf(line(1, "SKU-1", 4), line(2, "SKU-1", 3)), STOCK)
                .shouldBeInstanceOf<ReserveDecision.Reject>()
                .reply shouldBe ReserveReply.Rejected(RejectionReason.INSUFFICIENT_STOCK)
            InventoryRules
                .reserve(null, "saga-1", "order-1", listOf(line(1, "SKU-2", 99), line(2, "SKU-X", 1)), STOCK)
                .reply shouldBe ReserveReply.Rejected(RejectionReason.UNKNOWN_SKU)
            // ちょうど引当できる数なら引き当てる
            InventoryRules.reserve(null, "s", "o", listOf(line(1, "SKU-1", 6)), STOCK).shouldBeInstanceOf<ReserveDecision.Reserve>()
        }

        test("同じ Saga の 2 回目以降の引当の指示は、記録から決まる返事を返し直し、在庫を変えない") {
            fun replay(existing: Reservation) = InventoryRules.reserve(existing, "saga-1", "order-1", listOf(line(1, "SKU-1", 1)), STOCK)
            replay(Reservation("saga-1", "order-1", ReservationStatus.RESERVED)) shouldBe ReserveDecision.Replay(ReserveReply.Reserved)
            replay(Reservation("saga-1", "order-1", ReservationStatus.REJECTED, RejectionReason.INSUFFICIENT_STOCK)) shouldBe
                ReserveDecision.Replay(ReserveReply.Rejected(RejectionReason.INSUFFICIENT_STOCK))
            replay(Reservation("saga-1", "order-1", ReservationStatus.RELEASED)) shouldBe
                ReserveDecision.Replay(ReserveReply.Rejected(RejectionReason.ALREADY_RELEASED))
        }

        test("解放が先に届くと「取消済み」の印を作り、後から届いた引当の指示を ALREADY_RELEASED で拒否する(ADR-0029 §5)") {
            val marked =
                InventoryRules
                    .release(
                        null,
                        "saga-1",
                        "order-1",
                    ).shouldBeInstanceOf<ReleaseDecision.MarkReleasedBeforeReservation>()
            marked.outcome shouldBe ReleaseOutcome.NOT_RESERVED
            marked.marker shouldBe Reservation("saga-1", "order-1", ReservationStatus.RELEASED_BEFORE_RESERVATION)

            InventoryRules.reserve(marked.marker, "saga-1", "order-1", listOf(line(1, "SKU-1", 1)), STOCK) shouldBe
                ReserveDecision.Replay(ReserveReply.Rejected(RejectionReason.ALREADY_RELEASED))
        }

        test("解放: 引き当てていれば戻す。解放済み・拒否・印なら何も変えずに返し直す(何度届いても在庫は 1 回だけ戻る)") {
            val reserved =
                Reservation("saga-1", "order-1", ReservationStatus.RESERVED, lines = listOf(line(1, "SKU-1", 2), line(2, "SKU-1", 1)))
            val released = InventoryRules.release(reserved, "saga-1", "order-1").shouldBeInstanceOf<ReleaseDecision.Release>()
            released.decrements shouldBe mapOf("SKU-1" to 3L)
            released.released.status shouldBe ReservationStatus.RELEASED
            released.outcome shouldBe ReleaseOutcome.RELEASED

            InventoryRules.release(released.released, "saga-1", "order-1") shouldBe ReleaseDecision.Replay(ReleaseOutcome.RELEASED)
            InventoryRules.release(
                Reservation("saga-1", "order-1", ReservationStatus.REJECTED, RejectionReason.UNKNOWN_SKU),
                "saga-1",
                "order-1",
            ) shouldBe ReleaseDecision.Replay(ReleaseOutcome.NOT_RESERVED)
            InventoryRules.release(
                Reservation("saga-1", "order-1", ReservationStatus.RELEASED_BEFORE_RESERVATION),
                "saga-1",
                "order-1",
            ) shouldBe
                ReleaseDecision.Replay(ReleaseOutcome.NOT_RESERVED)
        }

        test("終わった状態は、有効な引当(RESERVED)以外") {
            ReservationStatus.entries.filter { !it.isSettled } shouldBe listOf(ReservationStatus.RESERVED)
        }

        test("値の検査: 明細の行番号・SKU・数量、Saga ID と注文 ID") {
            (StockLine.of(0, " ", 0) as Result.Err).error.violations.map { it.field } shouldBe
                listOf("lines.lineNumber", "lines.sku", "lines.quantity")
            (StockLine.of(1, "x".repeat(MAX_IDENTIFIER_LENGTH + 1), 1) is Result.Err) shouldBe true
            (validateIds("", "o".repeat(MAX_IDENTIFIER_LENGTH + 1)) as Result.Err).error.violations.map { it.field } shouldBe
                listOf("sagaId", "orderId")
            (validateIds("saga-1", "order-1") is Result.Ok) shouldBe true
            StockLevel("SKU-1", 10, 4).available shouldBe 6
        }

        test("保持期間は既定 30 日で、14 日(コマンドのトピックと DLQ の保持期間の合計)より短くはできない") {
            SettledRetention.DEFAULT.value shouldBe 30.days
            SettledRetention.of(14.days).ok().value shouldBe 14.days
            (SettledRetention.of(13.days) as Result.Err)
                .error.violations
                .single()
                .field shouldBe "retention"
        }
    })
