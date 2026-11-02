package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.SubmitSummary
import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.DocumentCrypto
import io.eia.b2bgateway.application.port.outbound.Opening
import io.eia.b2bgateway.application.port.outbound.OrderApi
import io.eia.b2bgateway.application.port.outbound.OrderSubmissions
import io.eia.b2bgateway.application.port.outbound.OriginalArchive
import io.eia.b2bgateway.application.port.outbound.PendingSubmission
import io.eia.b2bgateway.application.port.outbound.Placement
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.application.port.outbound.StoredOriginal
import io.eia.b2bgateway.application.usecase.AcknowledgementPorts
import io.eia.b2bgateway.application.usecase.SubmitOrdersService
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.ServiceCharacters
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val NOW = Instant.parse("2026-11-02T00:00:00Z")
private val PARTNER = PartyIdentification("4912345000019", "14")
private val OURS = PartyIdentification("4900000000001", "14")
private val HEADER = InterchangeHeader(ServiceCharacters.FALLBACK, CharacterSet.UNOA, "3", PARTNER, OURS, "ICR1", false)

private data object OrderDown : DomainError.Retryable {
    override val code: String = "order_down"
    override val message: String = "order の API を使えません"
}

private fun submission(
    id: String,
    positiveAperak: Boolean = true,
    retryUntil: Instant = NOW + 1.hours,
) = PendingSubmission(
    id = id,
    documentId = "doc-$id",
    partnerId = "partner01",
    orderNumber = "PO-$id",
    idempotencyKey = "b2b:partner01:ICR1:$id",
    body = "{\"id\":\"$id\"}".encodeToByteArray(),
    header = HEADER,
    positiveAperak = positiveAperak,
    retryUntil = retryUntil,
)

/** 送信待ち・order の API・応答の組み立ての Port の代わり。記録した結果と、保管した APERAK(平文)を残す。 */
private class SubmitFakes(
    val waiting: List<PendingSubmission>,
    val placements: Map<String, Result<Placement, DomainError>> = emptyMap(),
) : OrderSubmissions,
    OrderApi,
    ControlReferences,
    DocumentCrypto,
    OriginalArchive {
    val recorded = mutableListOf<String>()
    val aperaks = mutableListOf<String>()
    val sent = mutableListOf<Pair<String, String>>()
    private var reference = 0

    override suspend fun pending(limit: Int): Result<List<PendingSubmission>, DomainError> = ok(waiting.take(limit))

    override suspend fun accepted(
        submission: PendingSubmission,
        orderId: String,
        acknowledgement: StoredAcknowledgement?,
    ): Result<Unit, DomainError> {
        recorded += "accepted:${submission.id}:$orderId:${acknowledgement?.original?.ref}"
        return ok(Unit)
    }

    override suspend fun refused(
        submission: PendingSubmission,
        status: Int,
        acknowledgement: StoredAcknowledgement,
    ): Result<Unit, DomainError> {
        recorded += "refused:${submission.id}:$status:${acknowledgement.original.ref}"
        return ok(Unit)
    }

    override suspend fun expired(submission: PendingSubmission): Result<Unit, DomainError> {
        recorded += "expired:${submission.id}"
        return ok(Unit)
    }

    override suspend fun failed(
        submission: PendingSubmission,
        errorCode: String,
    ): Result<Unit, DomainError> {
        recorded += "failed:${submission.id}:$errorCode"
        return ok(Unit)
    }

    override suspend fun place(
        idempotencyKey: String,
        body: ByteArray,
    ): Result<Placement, DomainError> {
        sent += idempotencyKey to body.decodeToString()
        return placements[idempotencyKey] ?: ok(Placement.Accepted("ord-${idempotencyKey.substringAfterLast(':')}"))
    }

    override suspend fun next(recipient: PartyIdentification): Result<String, DomainError> = ok("ACK${++reference}")

    override suspend fun open(
        partnerId: String,
        content: ByteArray,
    ): Result<Opening, DomainError> = error("送信では復号しない")

    override suspend fun seal(
        partnerId: String,
        plain: ByteArray,
        literalName: String,
    ): Result<ByteArray, DomainError> = ok("sealed".encodeToByteArray() + plain)

    override suspend fun storeReceived(
        partnerId: String,
        content: ByteArray,
    ): Result<StoredOriginal, DomainError> = error("送信では受け取った原本を保管しない")

    override suspend fun storeReadable(
        partnerId: String,
        interchange: ByteArray,
        sha256: String,
    ): Result<String, DomainError> = error("送信では受け取った交換を保管しない")

    override suspend fun storeSent(
        partnerId: String,
        kind: AcknowledgementKind,
        sealed: ByteArray,
        plain: ByteArray,
    ): Result<StoredAcknowledgement, DomainError> {
        aperaks += plain.decodeToString()
        return ok(StoredAcknowledgement(kind, StoredOriginal("aperak-${aperaks.size}", "sha-${aperaks.size}"), "readable-${aperaks.size}"))
    }

    fun service() = SubmitOrdersService(this, this, AcknowledgementPorts(TestEdiSpecs, this, this, this), FixedClock(NOW))
}

private suspend fun SubmitFakes.submit(): SubmitSummary = service()().shouldBeInstanceOf<Result.Ok<SubmitSummary>>().value

class SubmitOrdersSpec :
    FunSpec({
        test("固定した本文を、冪等のキーを付けて送る。受け付けられたら、肯定の APERAK(AP)を組み立てて送信待ちにする") {
            val fakes = SubmitFakes(listOf(submission("1")))

            fakes.submit().accepted shouldContainExactly listOf("1")

            fakes.sent shouldContainExactly listOf("b2b:partner01:ICR1:1" to "{\"id\":\"1\"}")
            fakes.recorded shouldContainExactly listOf("accepted:1:ord-1:aperak-1")
            val aperak = fakes.aperaks.single()
            // 受け取った交換の宛先を入れ替え、受け取った文字セットで返す。注文番号を RFF+CO に入れる
            aperak shouldContain "UNB+UNOA:3+4900000000001:14+4912345000019:14+"
            aperak shouldContain "+ACK1'"
            aperak shouldContain "BGM+23+ACK1-1+9+AP'"
            aperak shouldContain "RFF+CO:PO-1'"
        }

        test("肯定の APERAK を選んでいない取引先には、受け付けられても APERAK を返さない") {
            val fakes = SubmitFakes(listOf(submission("1", positiveAperak = false)))

            fakes.submit().accepted shouldContainExactly listOf("1")

            fakes.recorded shouldContainExactly listOf("accepted:1:ord-1:null")
            fakes.aperaks.shouldBeEmpty()
        }

        test("業務の誤りで受け付けられなければ、APERAK(RE)を APR901 の固定の文言だけで返す(order の理由は入れない)") {
            val fakes =
                SubmitFakes(listOf(submission("1", positiveAperak = false)), mapOf("b2b:partner01:ICR1:1" to ok(Placement.Refused(422))))

            fakes.submit().refused shouldContainExactly listOf("1")

            fakes.recorded shouldContainExactly listOf("refused:1:422:aperak-1")
            val aperak = fakes.aperaks.single()
            aperak shouldContain "BGM+23+ACK1-1+9+RE'"
            aperak shouldContain "ERC+APR901::ZZZ'FTX+AAO+++ORDER NOT ACCEPTED BY SUPPLIER SYSTEM'"
            aperak shouldNotContain "422"
        }

        test("一時的な失敗は送信待ちのまま残し、ほかの注文は続ける。送り直しの上限を過ぎた注文は送らずに期限切れにする") {
            val fakes =
                SubmitFakes(
                    listOf(submission("1"), submission("2", retryUntil = NOW), submission("3")),
                    mapOf("b2b:partner01:ICR1:1" to err(OrderDown)),
                )

            val summary = fakes.submit()

            summary.failed.map { it.id to it.error } shouldContainExactly listOf("1" to OrderDown)
            summary.expired shouldContainExactly listOf("2")
            summary.accepted shouldContainExactly listOf("3")
            fakes.sent.map { it.first } shouldContainExactly listOf("b2b:partner01:ICR1:1", "b2b:partner01:ICR1:3")
            fakes.recorded shouldContainExactly listOf("failed:1:order_down", "expired:2", "accepted:3:ord-3:aperak-1")
        }

        test("送信待ちの一覧を読めなければ、そのエラー") {
            val fakes =
                object : OrderSubmissions by SubmitFakes(emptyList()) {
                    override suspend fun pending(limit: Int): Result<List<PendingSubmission>, DomainError> = err(OrderDown)
                }
            val ports = SubmitFakes(emptyList())

            val service = SubmitOrdersService(fakes, ports, AcknowledgementPorts(TestEdiSpecs, ports, ports, ports), FixedClock(NOW))

            service() shouldBe err(OrderDown)
        }
    })
