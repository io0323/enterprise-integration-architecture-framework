package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.AcceptDocumentUseCase
import io.eia.b2bgateway.application.port.inbound.AcceptedDocument
import io.eia.b2bgateway.application.port.inbound.DocumentOutcome
import io.eia.b2bgateway.application.port.inbound.IncomingDocument
import io.eia.b2bgateway.application.port.inbound.PollSummary
import io.eia.b2bgateway.application.port.outbound.PartnerInbox
import io.eia.b2bgateway.application.usecase.PollInboxService
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private data object InboxDown : DomainError.Retryable {
    override val code: String = "inbox_down"
    override val message: String = "取引先の SFTP に接続できません"
}

/** 取引先の受け取りのディレクトリと受け付けの代わり。押さえ・読み出し・処理済みへの移動を [calls] に残す。 */
private class InboxFakes(
    val ready: List<String> = emptyList(),
    val leftovers: List<String> = emptyList(),
    val taken: Set<String> = emptySet(),
    val failingAccept: Set<String> = emptySet(),
    val listFailure: DomainError? = null,
) : PartnerInbox,
    AcceptDocumentUseCase {
    val calls = mutableListOf<String>()

    override suspend fun ready(partnerId: String): Result<List<String>, DomainError> = listFailure?.let { err(it) } ?: ok(ready)

    override suspend fun claimed(partnerId: String): Result<List<String>, DomainError> = ok(leftovers)

    override suspend fun claim(
        partnerId: String,
        name: String,
    ): Result<Boolean, DomainError> {
        calls += "claim:$name"
        return ok(name !in taken)
    }

    override suspend fun read(
        partnerId: String,
        name: String,
    ): Result<ByteArray, DomainError> {
        calls += "read:$name"
        return ok(name.encodeToByteArray())
    }

    override suspend fun finish(
        partnerId: String,
        name: String,
        documentId: String,
    ): Result<Unit, DomainError> {
        calls += "finish:$name:$documentId"
        return ok(Unit)
    }

    override suspend fun invoke(document: IncomingDocument): Result<AcceptedDocument, DomainError> {
        calls += "accept:${document.fileName}:${document.content.decodeToString()}"
        return if (document.fileName in
            failingAccept
        ) {
            err(InboxDown)
        } else {
            ok(AcceptedDocument("doc-${document.fileName}", DocumentOutcome.CHECKED, null))
        }
    }

    fun service() = PollInboxService(this, this)
}

private suspend fun InboxFakes.poll(): PollSummary = service()("partner01").shouldBeInstanceOf<Result.Ok<PollSummary>>().value

class PollInboxSpec :
    FunSpec({
        test("前に押さえたまま残っていたファイルを先に処理し、書き終わったファイルを押さえて、読む → 受け付ける → 処理済みにする") {
            val fakes = InboxFakes(ready = listOf("a.pgp", "b.pgp"), leftovers = listOf("old.pgp"))

            val summary = fakes.poll()

            summary.accepted.map { it.documentId } shouldContainExactly listOf("doc-old.pgp", "doc-a.pgp", "doc-b.pgp")
            fakes.calls shouldContainExactly
                listOf(
                    "claim:a.pgp",
                    "claim:b.pgp",
                    "read:old.pgp",
                    "accept:old.pgp:old.pgp",
                    "finish:old.pgp:doc-old.pgp",
                    "read:a.pgp",
                    "accept:a.pgp:a.pgp",
                    "finish:a.pgp:doc-a.pgp",
                    "read:b.pgp",
                    "accept:b.pgp:b.pgp",
                    "finish:b.pgp:doc-b.pgp",
                )
        }

        test("ほかの処理が先に押さえたファイルは処理しない") {
            val fakes = InboxFakes(ready = listOf("a.pgp", "b.pgp"), taken = setOf("a.pgp"))

            fakes.poll().accepted.map { it.documentId } shouldContainExactly listOf("doc-b.pgp")
        }

        test("1 つのファイルの失敗はほかを止めない。失敗したファイルは処理済みにせず、押さえたまま残す") {
            val fakes = InboxFakes(ready = listOf("a.pgp", "b.pgp"), failingAccept = setOf("a.pgp"))

            val summary = fakes.poll()

            summary.failed.map { it.name to it.error } shouldContainExactly listOf("a.pgp" to InboxDown)
            summary.accepted.map { it.documentId } shouldContainExactly listOf("doc-b.pgp")
            fakes.calls.filter { it.startsWith("finish") } shouldContainExactly listOf("finish:b.pgp:doc-b.pgp")
        }

        test("一覧の失敗(取引先の SFTP に接続できない)は、そのエラー") {
            InboxFakes(listFailure = InboxDown).service()("partner01") shouldBe err(InboxDown)
        }
    })
