package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.OriginalMismatch
import io.eia.b2bgateway.application.port.inbound.SendSummary
import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.Acknowledgements
import io.eia.b2bgateway.application.port.outbound.Delivery
import io.eia.b2bgateway.application.port.outbound.Destination
import io.eia.b2bgateway.application.port.outbound.OriginalReader
import io.eia.b2bgateway.application.port.outbound.OutboundDocument
import io.eia.b2bgateway.application.port.outbound.PartnerOutbox
import io.eia.b2bgateway.application.port.outbound.PendingAcknowledgement
import io.eia.b2bgateway.application.port.outbound.StoredOriginal
import io.eia.b2bgateway.application.usecase.SendAcknowledgementsService
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private data object PartnerDown : DomainError.Retryable {
    override val code: String = "partner_down"
    override val message: String = "取引先の SFTP に接続できません"
}

private fun pending(
    id: String,
    kind: AcknowledgementKind = AcknowledgementKind.CONTRL,
) = PendingAcknowledgement(id, "doc-$id", "partner01", kind, StoredOriginal("originals/partner01/sent/$id.pgp#v1", "sha:$id"))

/** 送信待ちの応答・原本・送信の仕組みの代わり。送った文書と付けた印を残す。 */
private class SendFakes(
    val waiting: List<PendingAcknowledgement> = emptyList(),
    val contents: Map<String, String> = waiting.associate { it.original.ref to it.id },
    val present: Set<String> = emptySet(),
    val failingDelivery: Set<String> = emptySet(),
    val pendingFailure: DomainError? = null,
) : Acknowledgements,
    OriginalReader,
    PartnerOutbox {
    val delivered = mutableListOf<OutboundDocument>()
    val marked = mutableListOf<String>()

    override suspend fun pending(
        partnerId: String,
        limit: Int,
    ): Result<List<PendingAcknowledgement>, DomainError> = pendingFailure?.let { err(it) } ?: ok(waiting.take(limit))

    override suspend fun sent(
        acknowledgement: PendingAcknowledgement,
        remoteName: String,
        alreadyPresent: Boolean,
    ): Result<Unit, DomainError> {
        marked += "${acknowledgement.id}:$remoteName:$alreadyPresent"
        return ok(Unit)
    }

    override suspend fun read(ref: String): Result<ByteArray, DomainError> = ok(contents.getValue(ref).encodeToByteArray())

    override suspend fun deliver(
        partnerId: String,
        document: OutboundDocument,
    ): Result<Delivery, DomainError> {
        if (document.remoteName in failingDelivery) return err(PartnerDown)
        delivered += document
        return ok(if (document.remoteName in present) Delivery.ALREADY_PRESENT else Delivery.SENT)
    }

    fun service(batchSize: Int = 100) = SendAcknowledgementsService(this, this, this, { "sha:${it.decodeToString()}" }, batchSize)
}

private suspend fun SendFakes.send(batchSize: Int = 100): SendSummary =
    service(batchSize)("partner01").shouldBeInstanceOf<Result.Ok<SendSummary>>().value

class SendAcknowledgementsSpec :
    FunSpec({
        test("送信待ちの応答を、保管した原本の暗号文のまま(暗号化し直さずに)応答のディレクトリに送り、送った印を付ける") {
            val fakes = SendFakes(listOf(pending("a1"), pending("a2", AcknowledgementKind.APERAK)))

            fakes.send().sent shouldContainExactly listOf("a1", "a2")

            fakes.delivered.map { "${it.destination}:${it.remoteName}:${it.sealed}:${it.content.decodeToString()}" } shouldContainExactly
                listOf("ACKNOWLEDGEMENTS:contrl-a1.edi.pgp:true:a1", "ACKNOWLEDGEMENTS:aperak-a2.edi.pgp:true:a2")
            fakes.marked shouldContainExactly listOf("a1:contrl-a1.edi.pgp:false", "a2:aperak-a2.edi.pgp:false")
        }

        test("取引先の側にすでにあった応答は、すでにあったことを記録して送った印を付ける") {
            val fakes = SendFakes(listOf(pending("a1")), present = setOf("contrl-a1.edi.pgp"))

            fakes.send().sent shouldContainExactly listOf("a1")
            fakes.marked shouldContainExactly listOf("a1:contrl-a1.edi.pgp:true")
        }

        test("原本の SHA-256 が記録と一致しなければ送らない。1 件の失敗はほかの応答の送信を止めず、失敗した応答には印を付けない") {
            val mismatched = pending("a1")
            val fakes =
                SendFakes(
                    listOf(mismatched, pending("a2"), pending("a3")),
                    contents =
                        mapOf(
                            mismatched.original.ref to "tampered",
                            pending("a2").original.ref to "a2",
                            pending("a3").original.ref to "a3",
                        ),
                    failingDelivery = setOf("contrl-a2.edi.pgp"),
                )

            val summary = fakes.send()

            summary.sent shouldContainExactly listOf("a3")
            summary.failed.map { it.id to it.error } shouldContainExactly
                listOf("a1" to OriginalMismatch(mismatched.original.ref), "a2" to PartnerDown)
            fakes.delivered.map { it.remoteName } shouldContainExactly listOf("contrl-a3.edi.pgp")
            fakes.marked shouldContainExactly listOf("a3:contrl-a3.edi.pgp:false")
        }

        test("1 回で送るのは上限の件数まで(残りは次の回)") {
            SendFakes(listOf(pending("a1"), pending("a2"))).send(batchSize = 1).sent shouldContainExactly listOf("a1")
        }

        test("送信待ちの一覧を読めなければ、そのエラー") {
            SendFakes(pendingFailure = PartnerDown).service()("partner01") shouldBe err(PartnerDown)
        }
    })
