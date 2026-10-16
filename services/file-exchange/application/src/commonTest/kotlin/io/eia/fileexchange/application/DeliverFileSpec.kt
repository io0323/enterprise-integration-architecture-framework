package io.eia.fileexchange.application

import io.eia.fileexchange.application.port.inbound.PartnerDelivery
import io.eia.fileexchange.application.port.outbound.Deliveries
import io.eia.fileexchange.application.port.outbound.PartnerOutbox
import io.eia.fileexchange.application.port.outbound.Partners
import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.application.usecase.DeliverFileService
import io.eia.fileexchange.domain.Delivery
import io.eia.fileexchange.domain.DeliveryOutcome
import io.eia.fileexchange.domain.DeliveryState
import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.PartnerId
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val NAME = "sales_daily_20261014150000_001"
private const val DATA_FILE = "$NAME.parquet"
private const val REMOTE = "$DATA_FILE.pgp"
private val A = PartnerId("partner-a")
private val B = PartnerId("partner-b")
private val FAILURE = UnavailableError("接続先に接続できません")

/** 受け取りの記録の代わり(配送で使う find だけ)。 */
private class StoredFiles(
    var file: ReceivedFile?,
) : ReceivedFiles {
    override suspend fun find(name: String): Result<ReceivedFile?, DomainError> = ok(file?.takeIf { it.name == name })

    override suspend fun begin(file: ReceivedFile) = fail("呼ばれない")

    override suspend fun stored(name: String) = fail("呼ばれない")

    override suspend fun beginExtra(
        name: String,
        sha256: String?,
        reason: ExtraArrivalReason,
    ): Result<ExtraArrival, DomainError> = fail("呼ばれない")

    override suspend fun movingExtras() = fail("呼ばれない")

    override suspend fun finishExtra(id: Long) = fail("呼ばれない")
}

private class FakePartners(
    private val byDataset: Map<String, List<PartnerId>>,
) : Partners {
    override fun recipients(dataset: String): List<PartnerId> = byDataset[dataset].orEmpty()
}

/** 配送の記録の代わり(組ごとに 1 行の一意の制約も同じに振る舞う)。 */
private class FakeDeliveries : Deliveries {
    val records = mutableMapOf<Pair<String, PartnerId>, Delivery>()
    var failing = false

    private fun <T> guard(block: () -> T): Result<T, DomainError> = if (failing) err(FAILURE) else ok(block())

    override suspend fun find(
        name: String,
        partner: PartnerId,
    ) = guard { records[name to partner] }

    override suspend fun begin(
        name: String,
        partner: PartnerId,
        remoteName: String,
    ) = guard { records.getOrPut(name to partner) { Delivery(name, partner, remoteName, DeliveryState.SENDING, false) } }

    override suspend fun sent(
        name: String,
        partner: PartnerId,
        alreadyPresent: Boolean,
    ) = guard {
        records[name to partner] = records.getValue(name to partner).copy(state = DeliveryState.SENT, alreadyPresent = alreadyPresent)
    }
}

/** 送り先の代わり。[present] は相手の側にすでにある (取引先, 正式名)。[down] の取引先には接続できない。 */
private class FakeOutbox : PartnerOutbox {
    val present = mutableSetOf<Pair<PartnerId, String>>()
    val down = mutableSetOf<PartnerId>()
    var sendFails = false
    val calls = mutableListOf<String>()

    override suspend fun exists(
        partner: PartnerId,
        remoteName: String,
    ): Result<Boolean, DomainError> {
        calls += "exists $partner $remoteName"
        return if (partner in down) err(FAILURE) else ok(partner to remoteName in present)
    }

    override suspend fun send(
        partner: PartnerId,
        name: String,
        dataFile: String,
        remoteName: String,
    ): Result<Unit, DomainError> {
        calls += "send $partner $dataFile $remoteName"
        if (sendFails) return err(FAILURE)
        present += partner to remoteName
        return ok(Unit)
    }
}

private fun archived() = ReceivedFile(NAME, "a".repeat(64), ReceiptDestination.ARCHIVE, DATA_FILE, null, null, ReceiptState.STORED)

class DeliverFileSpec :
    FunSpec({
        val partners = FakePartners(mapOf("sales_daily" to listOf(A, B), "erp_orders" to listOf(B)))

        test("データセットを送る取引先ごとに、送信中を記録 → 正式名がないことを確かめる → 送る → 送信済み") {
            val deliveries = FakeDeliveries()
            val outbox = FakeOutbox()
            val report = DeliverFileService(StoredFiles(archived()), partners, deliveries, outbox)(NAME).ok()

            report.quarantined shouldBe false
            report.delivered shouldBe listOf(PartnerDelivery(A, DeliveryOutcome.SENT), PartnerDelivery(B, DeliveryOutcome.SENT))
            report.failures.shouldBeEmpty()
            outbox.calls shouldBe
                listOf("exists $A $REMOTE", "send $A $DATA_FILE $REMOTE", "exists $B $REMOTE", "send $B $DATA_FILE $REMOTE")
            deliveries.records.values
                .map { it.state }
                .toSet() shouldBe setOf(DeliveryState.SENT)
            deliveries.records.values
                .map { it.alreadyPresent }
                .toSet() shouldBe setOf(false)
        }

        test("送信中のまま残っていて、相手の側に正式名があれば、送らずに送信済みにする(正式名に変えた後に落ちた)") {
            val deliveries = FakeDeliveries()
            deliveries.records[NAME to A] = Delivery(NAME, A, REMOTE, DeliveryState.SENDING, false)
            val outbox = FakeOutbox().apply { present += A to REMOTE }
            val report = DeliverFileService(StoredFiles(archived()), partners, deliveries, outbox)(NAME).ok()

            report.delivered.first() shouldBe PartnerDelivery(A, DeliveryOutcome.ALREADY_PRESENT)
            outbox.calls.filter { it.startsWith("send $A") }.shouldBeEmpty()
            deliveries.records.getValue(NAME to A).state shouldBe DeliveryState.SENT
            deliveries.records.getValue(NAME to A).alreadyPresent shouldBe true
        }

        test("送信済みの取引先には何もしない(確かめにも行かない)") {
            val deliveries = FakeDeliveries()
            deliveries.records[NAME to A] = Delivery(NAME, A, REMOTE, DeliveryState.SENT, false)
            val outbox = FakeOutbox()
            val report = DeliverFileService(StoredFiles(archived()), partners, deliveries, outbox)(NAME).ok()

            report.delivered.first() shouldBe PartnerDelivery(A, DeliveryOutcome.ALREADY_SENT)
            outbox.calls.none { " $A " in it } shouldBe true
        }

        test("1 つの取引先に接続できなくても、ほかの取引先には送る。接続できなかった取引先は送信中のまま残る") {
            val deliveries = FakeDeliveries()
            val outbox = FakeOutbox().apply { down += A }
            val report = DeliverFileService(StoredFiles(archived()), partners, deliveries, outbox)(NAME).ok()

            report.delivered shouldBe listOf(PartnerDelivery(B, DeliveryOutcome.SENT))
            report.failures.map { it.partner to it.error } shouldBe listOf(A to FAILURE)
            deliveries.records.getValue(NAME to A).state shouldBe DeliveryState.SENDING
        }

        test("送信の失敗は送信中のまま残す(やり直しで、確かめてから送る)") {
            val deliveries = FakeDeliveries()
            val outbox = FakeOutbox().apply { sendFails = true }
            val service = DeliverFileService(StoredFiles(archived()), partners, deliveries, outbox)
            service(NAME).ok().failures.size shouldBe 2
            deliveries.records.values
                .map { it.state }
                .toSet() shouldBe setOf(DeliveryState.SENDING)

            outbox.sendFails = false
            service(NAME).ok().delivered.map { it.outcome } shouldBe listOf(DeliveryOutcome.SENT, DeliveryOutcome.SENT)
        }

        test("記録の失敗は取引先の失敗として返す") {
            val deliveries = FakeDeliveries().apply { failing = true }
            val report = DeliverFileService(StoredFiles(archived()), partners, deliveries, FakeOutbox())(NAME).ok()
            report.failures.map { it.error } shouldBe listOf(FAILURE, FAILURE)
        }

        test("quarantine に移したファイルは送らない") {
            val quarantined =
                archived().copy(destination = ReceiptDestination.QUARANTINE, dataFile = null, reason = "checksum_mismatch", detail = "x")
            val outbox = FakeOutbox()
            val report = DeliverFileService(StoredFiles(quarantined), partners, FakeDeliveries(), outbox)(NAME).ok()
            report.quarantined shouldBe true
            report.delivered.shouldBeEmpty()
            outbox.calls.shouldBeEmpty()
        }

        test("送る取引先のないデータセット・形の違う名前なら、何もしない") {
            val other = "misc_data_20261014150000_001"
            val outbox = FakeOutbox()
            DeliverFileService(StoredFiles(archived().copy(name = other)), partners, FakeDeliveries(), outbox)(other)
                .ok()
                .delivered
                .shouldBeEmpty()
            DeliverFileService(StoredFiles(archived().copy(name = "odd")), partners, FakeDeliveries(), outbox)("odd")
                .ok()
                .delivered
                .shouldBeEmpty()
            outbox.calls.shouldBeEmpty()
        }

        test("受け取りが終わっていない(記録がない・移動中)なら失敗") {
            val service = { file: ReceivedFile? -> DeliverFileService(StoredFiles(file), partners, FakeDeliveries(), FakeOutbox()) }
            (service(null)(NAME) as Result.Err).error.shouldBeInstanceOf<ConflictError>()
            (service(archived().copy(state = ReceiptState.MOVING))(NAME) as Result.Err).error.shouldBeInstanceOf<ConflictError>()
        }
    })
