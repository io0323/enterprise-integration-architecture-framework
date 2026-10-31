package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.AcceptedDocument
import io.eia.b2bgateway.application.port.inbound.DocumentOutcome
import io.eia.b2bgateway.application.port.inbound.IncomingDocument
import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.b2bgateway.application.port.inbound.ReceiveInterchangeUseCase
import io.eia.b2bgateway.application.port.inbound.ReceivedDocument
import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.DocumentCrypto
import io.eia.b2bgateway.application.port.outbound.DocumentRecord
import io.eia.b2bgateway.application.port.outbound.OpenedInterchange
import io.eia.b2bgateway.application.port.outbound.Opening
import io.eia.b2bgateway.application.port.outbound.OriginalArchive
import io.eia.b2bgateway.application.port.outbound.ReceiptRecords
import io.eia.b2bgateway.application.port.outbound.ReceivedFile
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.application.port.outbound.StoredOriginal
import io.eia.b2bgateway.application.usecase.AcceptDocumentService
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.ServiceCharacters
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.receipt.PreviousReceipt
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.b2bgateway.domain.validation.ServiceError
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-31T00:00:00Z")
private val SENDER = PartyIdentification("S", "14")
private val RECIPIENT = PartyIdentification("R", "14")
private val AGREEMENT = PartnerAgreement("partner01", SENDER, RECIPIENT, "R-GLN", setOf(Currency.JPY), true, 4.hours)
private val HEADER = InterchangeHeader(ServiceCharacters.FALLBACK, CharacterSet.UNOA, "3", SENDER, RECIPIENT, "ICR", test = false)

private fun receipt(
    result: InterchangeResult = InterchangeResult.Checked(HEADER, emptyList()),
    duplicate: DuplicateCheck = DuplicateCheck.First,
    contrl: String? = "CONTRL",
    aperak: String? = null,
) = Receipt(AGREEMENT, result, duplicate, emptyList(), contrl?.encodeToByteArray(), aperak?.encodeToByteArray())

private data object ArchiveDown : DomainError.Retryable {
    override val code: String = "store_down"
    override val message: String = "置き場を使えません"
}

/** 原本の保管・暗号・記録・3 段階の検査の代わり。呼ばれた順を [calls] に残す。 */
private class AcceptFakes(
    var opening: Opening = Opening.Opened("PLAIN".encodeToByteArray(), "plain-sha", "FP"),
    var receipt: Receipt = receipt(),
    var failArchive: Boolean = false,
) : OriginalArchive,
    DocumentCrypto,
    ReceiptRecords,
    ReceiveInterchangeUseCase {
    val calls = mutableListOf<String>()
    val records = mutableListOf<DocumentRecord>()
    var received: ReceivedDocument? = null

    override suspend fun storeReceived(
        partnerId: String,
        content: ByteArray,
    ): Result<StoredOriginal, DomainError> {
        calls += "storeReceived"
        return if (failArchive) err(ArchiveDown) else ok(StoredOriginal("originals/$partnerId/cipher", "cipher-sha"))
    }

    override suspend fun storeReadable(
        partnerId: String,
        interchange: ByteArray,
        sha256: String,
    ): Result<String, DomainError> {
        calls += "storeReadable:${interchange.decodeToString()}:$sha256"
        return ok("archive/$partnerId/$sha256")
    }

    override suspend fun storeSent(
        partnerId: String,
        kind: AcknowledgementKind,
        sealed: ByteArray,
        plain: ByteArray,
    ): Result<StoredAcknowledgement, DomainError> {
        calls += "storeSent:$kind:${sealed.decodeToString()}"
        return ok(StoredAcknowledgement(kind, StoredOriginal("originals/$partnerId/$kind", "$kind-sha"), "archive/$partnerId/$kind"))
    }

    override suspend fun open(
        partnerId: String,
        content: ByteArray,
    ): Result<Opening, DomainError> {
        calls += "open"
        return ok(opening)
    }

    override suspend fun seal(
        partnerId: String,
        plain: ByteArray,
    ): Result<ByteArray, DomainError> = ok("sealed(${plain.decodeToString()})".encodeToByteArray())

    override suspend fun record(document: DocumentRecord): Result<String, DomainError> {
        calls += "record"
        records += document
        return ok("doc-1")
    }

    override suspend fun invoke(document: ReceivedDocument): Result<Receipt, DomainError> {
        calls += "receive"
        received = document
        return ok(receipt)
    }

    fun service() = AcceptDocumentService(this, this, this, this, FixedClock(NOW))
}

private suspend fun AcceptFakes.accept(): AcceptedDocument =
    service()(IncomingDocument("partner01", "orders-1.edi.pgp", "CIPHER".encodeToByteArray()))
        .shouldBeInstanceOf<Result.Ok<AcceptedDocument>>()
        .value

class AcceptDocumentSpec :
    FunSpec({
        test("原本を最初に保管し、復号した交換を保管してから検査する。応答は暗号化して原本として保管し、最後に 1 回だけ記録する") {
            val fakes = AcceptFakes(receipt = receipt(aperak = "APERAK"))

            val accepted = fakes.accept()

            accepted.outcome shouldBe DocumentOutcome.CHECKED
            accepted.documentId shouldBe "doc-1"
            fakes.calls shouldContainExactly
                listOf(
                    "storeReceived",
                    "open",
                    "storeReadable:PLAIN:plain-sha",
                    "receive",
                    "storeSent:CONTRL:sealed(CONTRL)",
                    "storeSent:APERAK:sealed(APERAK)",
                    "record",
                )
            fakes.received?.sha256 shouldBe "plain-sha"
            with(fakes.records.single()) {
                file shouldBe ReceivedFile("partner01", "orders-1.edi.pgp", NOW, StoredOriginal("originals/partner01/cipher", "cipher-sha"))
                opened shouldBe OpenedInterchange("plain-sha", "FP", "archive/partner01/plain-sha")
                refusal shouldBe null
                acknowledgements.map { it.kind } shouldContainExactly listOf(AcknowledgementKind.CONTRL, AcknowledgementKind.APERAK)
            }
        }

        test("復号や署名の検証に失敗した文書も、原本を保管し、「復号の段階で拒否」として記録する(検査も応答もしない)") {
            val fakes = AcceptFakes(opening = Opening.Refused("pgp_unknown_signer"))

            val accepted = fakes.accept()

            accepted.outcome shouldBe DocumentOutcome.UNREADABLE
            accepted.receipt shouldBe null
            fakes.calls shouldContainExactly listOf("storeReceived", "open", "record")
            with(fakes.records.single()) {
                refusal shouldBe "pgp_unknown_signer"
                file.original.sha256 shouldBe "cipher-sha"
                opened shouldBe null
                receipt shouldBe null
                acknowledgements.shouldBeEmpty()
            }
        }

        test("結果の分類: 送り直し・UNB を読めない・交換の拒否") {
            AcceptFakes(
                receipt = receipt(duplicate = DuplicateCheck.Resend(PreviousReceipt("plain-sha", "doc-0")), contrl = null),
            ).accept().let {
                it.outcome shouldBe DocumentOutcome.RESENT
            }
            AcceptFakes(
                receipt = receipt(result = InterchangeResult.Unreadable(ServiceError(SyntaxErrorCode.MISSING, "UNB")), contrl = null),
            ).accept()
                .outcome shouldBe DocumentOutcome.NO_ENVELOPE
            AcceptFakes(receipt = receipt(result = InterchangeResult.Rejected(HEADER, ServiceError(SyntaxErrorCode.MISSING, "UNA"))))
                .accept()
                .outcome shouldBe DocumentOutcome.REJECTED
        }

        test("送り直しでは新しい応答を保管しない(前の記録の応答を、記録の側が送り直す)") {
            val fakes =
                AcceptFakes(receipt = receipt(duplicate = DuplicateCheck.Resend(PreviousReceipt("plain-sha", "doc-0")), contrl = null))

            fakes.accept()

            fakes.calls.filter { it.startsWith("storeSent") }.shouldBeEmpty()
            fakes.records
                .single()
                .acknowledgements
                .shouldBeEmpty()
        }

        test("原本を保管できなければ、何もせずにエラーを返す(ファイルは取引先の SFTP に残し、やり直す)") {
            val fakes = AcceptFakes(failArchive = true)

            fakes.service()(IncomingDocument("partner01", "f", byteArrayOf())) shouldBe err(ArchiveDown)
            fakes.calls shouldContainExactly listOf("storeReceived")
        }
    })
