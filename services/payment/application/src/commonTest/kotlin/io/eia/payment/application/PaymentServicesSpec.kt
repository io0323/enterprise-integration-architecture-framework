package io.eia.payment.application

import io.eia.payment.application.port.inbound.CommandEnvelope
import io.eia.payment.application.port.inbound.CommandOutcome
import io.eia.payment.application.port.inbound.PurgedRecords
import io.eia.payment.application.port.outbound.AuthorizationStore
import io.eia.payment.application.port.outbound.PaymentReplies
import io.eia.payment.application.port.outbound.ProcessedCommands
import io.eia.payment.application.port.outbound.TransactionRunner
import io.eia.payment.application.usecase.AuthorizePaymentService
import io.eia.payment.application.usecase.PurgeExpiredRecordsService
import io.eia.payment.application.usecase.VoidPaymentService
import io.eia.payment.domain.Amount
import io.eia.payment.domain.Authorization
import io.eia.payment.domain.AuthorizationStatus
import io.eia.payment.domain.AuthorizeReply
import io.eia.payment.domain.DeclineReason
import io.eia.payment.domain.PaymentRules
import io.eia.payment.domain.SettledRetention
import io.eia.payment.domain.VoidOutcome
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
private class World {
    val processed = mutableSetOf<String>()
    val records = mutableMapOf<String, Authorization>()
    val replies = mutableListOf<String>()
    var failReplies = false
    var purgeBatches = mutableListOf(0)
    var processedPurges = mutableListOf(0)
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

            override suspend fun purgeExpired(batchSize: Int): Result<Int, DomainError> = ok(processedPurges.removeFirstOrNull() ?: 0)
        }
    val store =
        object : AuthorizationStore {
            override suspend fun findForUpdate(sagaId: String): Result<Authorization?, DomainError> = ok(records[sagaId])

            override suspend fun insert(authorization: Authorization): Result<Unit, DomainError> {
                check(authorization.sagaId !in records) { "同じ Saga の行を 2 回書いた" }
                records[authorization.sagaId] = authorization
                return ok(Unit)
            }

            override suspend fun markVoided(sagaId: String): Result<Unit, DomainError> {
                records[sagaId] = records.getValue(sagaId).copy(status = AuthorizationStatus.VOIDED)
                return ok(Unit)
            }

            override suspend fun purgeSettled(
                retention: Duration,
                batchSize: Int,
            ): Result<Int, DomainError> = ok(purgeBatches.removeFirstOrNull() ?: 0)
        }
    val paymentReplies =
        object : PaymentReplies {
            override suspend fun authorizeReplied(
                sagaId: String,
                orderId: String,
                reply: AuthorizeReply,
            ): Result<Unit, DomainError> = record("$sagaId:$reply")

            override suspend fun voided(
                sagaId: String,
                orderId: String,
                outcome: VoidOutcome,
            ): Result<Unit, DomainError> = record("$sagaId:$outcome")

            fun record(reply: String): Result<Unit, DomainError> =
                if (failReplies) {
                    err(UnavailableError("Outbox に書けない"))
                } else {
                    replies += reply
                    ok(Unit)
                }
        }
    private val rules = PaymentRules(Amount(1_000, "JPY"))
    val authorize = AuthorizePaymentService(transactions, processedCommands, store, paymentReplies, rules) { "auth-${++ids}" }
    val void = VoidPaymentService(transactions, processedCommands, store, paymentReplies, rules)
}

private fun envelope(id: String) = CommandEnvelope(id, "payment.payment.cmd-authorize.v1", "saga-1", "order-1")

class PaymentServicesSpec :
    FunSpec({
        test("承認して返事を書く。同じ ce_id は DUPLICATE、同じ Saga の別の ce_id は同じ承認 ID を返し直す") {
            val world = World()
            world.authorize(envelope("m-1"), "cust-1", Amount(500, "JPY")).ok() shouldBe CommandOutcome.PROCESSED
            world.authorize(envelope("m-1"), "cust-1", Amount(500, "JPY")).ok() shouldBe CommandOutcome.DUPLICATE
            world.authorize(envelope("m-2"), "cust-1", Amount(500, "JPY")).ok() shouldBe CommandOutcome.PROCESSED

            world.replies shouldBe listOf("saga-1:${AuthorizeReply.Authorized("auth-1")}", "saga-1:${AuthorizeReply.Authorized("auth-1")}")
        }

        test("上限を超えれば拒否を記録して LIMIT_EXCEEDED を返す") {
            val world = World()
            world.authorize(envelope("m-1"), "cust-1", Amount(5_000, "JPY")).ok()
            world.records.getValue("saga-1").status shouldBe AuthorizationStatus.DECLINED
            world.replies shouldBe listOf("saga-1:${AuthorizeReply.Declined(DeclineReason.LIMIT_EXCEEDED)}")
        }

        test("取消: 承認を取り消し、2 回目以降は VOIDED を返し直す") {
            val world = World()
            world.authorize(envelope("m-1"), "cust-1", Amount(500, "JPY")).ok()
            world.void(envelope("m-2")).ok()
            world.void(envelope("m-3")).ok()
            world.records.getValue("saga-1").status shouldBe AuthorizationStatus.VOIDED
            world.replies.drop(1) shouldBe listOf("saga-1:VOIDED", "saga-1:VOIDED")
        }

        test("取消が先に届けば印を作り(NOT_AUTHORIZED)、後から届いた承認の指示を ALREADY_VOIDED で拒否する") {
            val world = World()
            world.void(envelope("m-1")).ok()
            world.authorize(envelope("m-2"), "cust-1", Amount(500, "JPY")).ok()
            world.records.getValue("saga-1").status shouldBe AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION
            world.replies shouldBe listOf("saga-1:NOT_AUTHORIZED", "saga-1:${AuthorizeReply.Declined(DeclineReason.ALREADY_VOIDED)}")
        }

        test("返事を書けなければ、記録と冪等消費の記録も取り消す") {
            val world = World()
            world.failReplies = true
            world.authorize(envelope("m-1"), "cust-1", Amount(500, "JPY")).shouldBeInstanceOf<Result.Err<DomainError>>()
            world.processed shouldBe emptySet()
            world.records shouldBe emptyMap()
        }

        test("識別子の誤りは、トランザクションの前に ValidationError") {
            val world = World()
            (world.authorize(envelope("m-1"), "", Amount(1, "JPY")) as Result.Err).error.shouldBeInstanceOf<ValidationError>()
            (world.void(CommandEnvelope("m-1", "t", "", "o")) as Result.Err).error.shouldBeInstanceOf<ValidationError>()
            world.processed shouldBe emptySet()
        }

        test("保持期間を過ぎた記録を、上限より少なく消えるまで繰り返し消す") {
            val world = World()
            world.purgeBatches = mutableListOf(2, 1)
            world.processedPurges = mutableListOf(0)
            PurgeExpiredRecordsService(
                world.store,
                world.processedCommands,
                (SettledRetention.of(20.days) as Result.Ok).value,
                batchSize = 2,
            ).invoke()
                .ok() shouldBe PurgedRecords(records = 3, processedMessages = 0)
        }
    })
