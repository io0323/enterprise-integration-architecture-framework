package io.eia.webhookreceiver.application

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhook
import io.eia.webhookreceiver.application.port.outbound.IngestOutbox
import io.eia.webhookreceiver.application.port.outbound.TransactionRunner
import io.eia.webhookreceiver.application.port.outbound.WebhookReceipts
import io.eia.webhookreceiver.application.usecase.PurgeReceiptsService
import io.eia.webhookreceiver.application.usecase.ReceiveWebhookService
import io.eia.webhookreceiver.domain.ReceiptOutcome
import io.eia.webhookreceiver.domain.ReceiptRetention
import io.eia.webhookreceiver.domain.WebhookReceipt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-21T00:00:10Z")
private val SIGNED = Instant.parse("2026-10-21T00:00:00Z")

/** トランザクションの範囲: Err なら、その中で書いた記録を取り消す。 */
private class FakeTransactions(
    private val receipts: FakeReceipts,
) : TransactionRunner {
    var committed = 0
    var rolledBack = 0

    override suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError> {
        val before = receipts.stored.toMap()
        return block().also {
            if (it is Result.Err) {
                rolledBack++
                receipts.stored.clear()
                receipts.stored.putAll(before)
            } else {
                committed++
            }
        }
    }
}

private class FakeReceipts : WebhookReceipts {
    val stored = mutableMapOf<String, WebhookReceipt>()
    var failure: DomainError? = null
    val purged = ArrayDeque<Int>()
    var purgeCalls = mutableListOf<Pair<Duration, Int>>()

    override suspend fun insertIfAbsent(receipt: WebhookReceipt): Result<WebhookReceipt?, DomainError> {
        failure?.let { return err(it) }
        val existing = stored[receipt.webhookId]
        if (existing == null) stored[receipt.webhookId] = receipt
        return ok(existing)
    }

    override suspend fun purgeOlderThan(
        retention: Duration,
        limit: Int,
    ): Result<Int, DomainError> {
        purgeCalls += retention to limit
        failure?.let { return err(it) }
        return ok(purged.removeFirstOrNull() ?: 0)
    }
}

private class FakeOutbox : IngestOutbox {
    val written = mutableListOf<Triple<WebhookReceipt, String, ByteArray>>()
    var failure: DomainError? = null

    override suspend fun received(
        receipt: WebhookReceipt,
        contentType: String,
        body: ByteArray,
    ): Result<Unit, DomainError> {
        failure?.let { return err(it) }
        written += Triple(receipt, contentType, body)
        return ok(Unit)
    }
}

private fun command(
    id: String = "evt_1",
    body: String = """{"type":"customer.created"}""",
    sha: String = "a".repeat(64),
) = ReceiveWebhook(id, "crm", SIGNED, "application/json", sha, body.encodeToByteArray())

class WebhookServicesSpec :
    FunSpec({
        lateinit var receipts: FakeReceipts
        lateinit var outbox: FakeOutbox
        lateinit var transactions: FakeTransactions
        lateinit var service: ReceiveWebhookService
        beforeTest {
            receipts = FakeReceipts()
            outbox = FakeOutbox()
            transactions = FakeTransactions(receipts)
            service = ReceiveWebhookService(transactions, receipts, outbox, FixedClock(NOW))
        }

        test("新しい ID は、受け取りの記録と Outbox を同じトランザクションで書く(本文はそのまま Outbox に渡す)") {
            service(command()) shouldBe ok(ReceiptOutcome.Recorded)
            transactions.committed shouldBe 1
            val stored = receipts.stored.getValue("evt_1")
            stored.signedAt shouldBe SIGNED
            stored.receivedAt shouldBe NOW
            stored.bodySha256 shouldBe "a".repeat(64)
            val (receipt, contentType, body) = outbox.written.single()
            receipt shouldBe stored
            contentType shouldBe "application/json"
            body.decodeToString() shouldBe """{"type":"customer.created"}"""
        }

        test("同じ ID の再送は、記録も Outbox も書かない。本文が違えばそれを返す") {
            service(command())
            service(command()) shouldBe ok(ReceiptOutcome.Duplicate(sameBody = true))
            service(command(sha = "b".repeat(64))) shouldBe ok(ReceiptOutcome.Duplicate(sameBody = false))
            outbox.written.size shouldBe 1
            receipts.stored.size shouldBe 1
        }

        test("Outbox に書けなければ、受け取りの記録も取り消す(送り手の再送で、次は記録できる)") {
            outbox.failure = UnavailableError("outbox")
            service(command()).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            transactions.rolledBack shouldBe 1
            receipts.stored.size shouldBe 0

            outbox.failure = null
            service(command()) shouldBe ok(ReceiptOutcome.Recorded)
        }

        test("記録に書けなければ Err。形の誤り(イベント ID など)はトランザクションを開かずに Err") {
            receipts.failure = UnavailableError("db")
            service(command()).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            outbox.written.size shouldBe 0

            service(command(id = "bad id")).shouldBeInstanceOf<Result.Err<ValidationError>>()
            transactions.committed + transactions.rolledBack shouldBe 1
        }

        test("保持期間を過ぎた記録を、少なく消えるまで消す。失敗はそのまま返す") {
            receipts.purged.addAll(listOf(2, 2, 1))
            val purge = PurgeReceiptsService(receipts, ReceiptRetention.DEFAULT, batchSize = 2)
            purge() shouldBe ok(5)
            receipts.purgeCalls.size shouldBe 3
            receipts.purgeCalls.first() shouldBe (ReceiptRetention.DEFAULT.value to 2)

            receipts.failure = UnavailableError("db")
            purge().shouldBeInstanceOf<Result.Err<UnavailableError>>()
        }

        test("ログに本文を出さない") {
            command().toString() shouldBe "ReceiveWebhook(webhookId=evt_1, source=crm, signedAt=$SIGNED, body=27 bytes)"
        }
    })
